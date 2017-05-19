/**
 * Copyright 2016 benjobs
 * <p>
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.opencron.server.controller;

import java.util.*;

import com.alibaba.fastjson.JSON;
import org.opencron.common.job.Opencron;
import org.opencron.common.job.Opencron.ExecType;
import org.opencron.common.utils.DigestUtils;
import org.opencron.common.utils.StringUtils;
import org.opencron.server.domain.Job;
import org.opencron.server.job.OpencronTools;
import org.opencron.server.service.*;
import org.opencron.server.tag.PageBean;
import org.opencron.common.utils.CommonUtils;
import org.opencron.common.utils.WebUtils;
import org.opencron.server.domain.Agent;
import org.opencron.server.vo.JobVo;
import org.quartz.SchedulerException;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import static org.opencron.common.utils.CommonUtils.notEmpty;

@Controller
@RequestMapping("/job")
public class JobController extends BaseController {

    @Autowired
    private ExecuteService executeService;

    @Autowired
    private JobService jobService;

    @Autowired
    private AgentService agentService;

    @Autowired
    private RecordService recordService;

    @Autowired
    private SchedulerService schedulerService;

    @RequestMapping("/view")
    public String view(HttpSession session, HttpServletRequest request, PageBean pageBean, JobVo job, Model model) {

        model.addAttribute("agents", agentService.getOwnerAgents(session));

        model.addAttribute("jobs", jobService.getAll());
        if (notEmpty(job.getAgentId())) {
            model.addAttribute("agentId", job.getAgentId());
        }
        if (notEmpty(job.getCronType())) {
            model.addAttribute("cronType", job.getCronType());
        }
        if (notEmpty(job.getJobType())) {
            model.addAttribute("jobType", job.getJobType());
        }
        if (notEmpty(job.getExecType())) {
            model.addAttribute("execType", job.getExecType());
        }
        if (notEmpty(job.getRedo())) {
            model.addAttribute("redo", job.getRedo());
        }
        jobService.getJobVos(session, pageBean, job);
        if (request.getParameter("refresh") != null) {
            return "/job/refresh";
        }
        return "/job/view";
    }

    /**
     * 同一台执行器上不能有重复名字的job
     *
     * @param response
     * @param jobId
     * @param agentId
     * @param name
     */
    @RequestMapping("/checkname")
    public void checkName(HttpServletResponse response, Long jobId, Long agentId, String name) {
        boolean result = jobService.existsName(jobId, agentId, name);
        WebUtils.writeHtml(response, result ? "false" : "true");
    }

    @RequestMapping("/checkDelete")
    public void checkDelete(HttpServletResponse response, Long id) {
        String result = jobService.checkDelete(id);
        WebUtils.writeHtml(response, result);
    }

    @RequestMapping("/delete")
    public void delete(HttpServletResponse response, Long id) {
        try {
            jobService.delete(id);
            WebUtils.writeHtml(response, "true");
        } catch (SchedulerException e) {
            WebUtils.writeHtml(response, "false");
            e.printStackTrace();
        }
    }

    @RequestMapping("/addpage")
    public String addpage(HttpSession session, Model model, Long id) {
        if (notEmpty(id)) {
            Agent agent = agentService.getAgent(id);
            model.addAttribute("agent", agent);
        }
        List<Agent> agents = agentService.getOwnerAgents(session);
        model.addAttribute("agents", agents);
        return "/job/add";
    }

    @RequestMapping(value = "/save")
    public String save(HttpSession session, Job job, HttpServletRequest request) throws SchedulerException {
        job.setCommand(DigestUtils.passBase64(job.getCommand()));
        job.setDeleted(false);
        if (job.getJobId() != null) {
            Job job1 = jobService.getJob(job.getJobId());
            if (!jobService.checkJobOwner(session, job1.getUserId()))
                return "redirect:/job/view?csrf=" + OpencronTools.getCSRF(session);
            /**
             * 将数据库中持久化的作业和当前修改的合并,当前修改的属性覆盖持久化的属性...
             */
            BeanUtils.copyProperties(job1, job, "jobName", "cronType", "cronExp", "command", "execType", "comment", "redo", "runCount", "jobType", "runModel", "warning", "mobiles", "emailAddress", "timeout");
        }

        //单任务
        if (Opencron.JobType.SINGLETON.getCode().equals(job.getJobType())) {
            job.setUserId(OpencronTools.getUserId(session));
            job.setUpdateTime(new Date());
            job.setLastChild(false);
            job = jobService.merge(job);
        } else { //流程任务
            Map<String, String[]> map = request.getParameterMap();
            Object[] jobName = map.get("child.jobName");
            Object[] jobId = map.get("child.jobId");
            Object[] agentId = map.get("child.agentId");
            Object[] command = map.get("child.command");
            Object[] redo = map.get("child.redo");
            Object[] runCount = map.get("child.runCount");
            Object[] timeout = map.get("child.timeout");
            Object[] comment = map.get("child.comment");
            List<Job> children = new ArrayList<Job>(0);
            for (int i = 0; i < jobName.length; i++) {
                Job child = new Job();
                if (CommonUtils.notEmpty(jobId[i])) {
                    //子任务修改的..
                    Long jobid = Long.parseLong((String) jobId[i]);
                    child = jobService.getJob(jobid);
                }
                /**
                 * 新增并行和串行,子任务和最顶层的父任务一样
                 */
                child.setRunModel(job.getRunModel());
                child.setJobName(StringUtils.htmlEncode((String) jobName[i]));
                child.setAgentId(Long.parseLong((String) agentId[i]));
                child.setCommand(DigestUtils.passBase64((String) command[i]));
                child.setJobType(Opencron.JobType.FLOW.getCode());
                child.setComment(StringUtils.htmlEncode((String) comment[i]));
                child.setTimeout(Integer.parseInt((String) timeout[i]));
                child.setRedo(Integer.parseInt((String) redo[i]));
                child.setDeleted(false);
                if (child.getRedo() == 0) {
                    child.setRunCount(null);
                } else {
                    child.setRunCount(Integer.parseInt((String) runCount[i]));
                }
                children.add(child);
            }

            //流程任务必须有子任务,没有的话不保存
            if (CommonUtils.isEmpty(children)) {
                return "redirect:/job/view?csrf=" + OpencronTools.getCSRF(session);
            }

            if (job.getUserId() == null) {
                job.setUserId(OpencronTools.getUserId(session));
            }

            jobService.saveFlowJob(job, children);
        }

        schedulerService.syncJobTigger(job.getJobId(), executeService);

        return "redirect:/job/view?csrf=" + OpencronTools.getCSRF(session);
    }

    @RequestMapping("/editsingle")
    public void editSingleJob(HttpSession session, HttpServletResponse response, Long id) {
        JobVo job = jobService.getJobVoById(id);
        if (job == null) {
            WebUtils.writeJson(response, "404");
        }
        if (!jobService.checkJobOwner(session, job.getUserId())) return;
        WebUtils.writeJson(response, JSON.toJSONString(job));
    }

    @RequestMapping("/editflow")
    public String editFlowJob(HttpSession session, Model model, Long id) {
        JobVo job = jobService.getJobVoById(id);
        if (job == null) {
            return "/error/404";
        }
        if (!jobService.checkJobOwner(session, job.getUserId()))
            return "redirect:/job/view?csrf=" + OpencronTools.getCSRF(session);
        model.addAttribute("job", job);
        List<Agent> agents = agentService.getOwnerAgents(session);
        model.addAttribute("agents", agents);
        return "/job/edit";
    }


    @RequestMapping("/edit")
    public void edit(HttpSession session, HttpServletResponse response, Job job) throws SchedulerException {
        Job dbJob = jobService.getJob(job.getJobId());
        if (!jobService.checkJobOwner(session, dbJob.getUserId())) return;
        dbJob.setExecType(job.getExecType());
        dbJob.setCronType(job.getCronType());
        dbJob.setCronExp(job.getCronExp());
        dbJob.setCommand(DigestUtils.passBase64(job.getCommand()));
        dbJob.setJobName(job.getJobName());
        dbJob.setRedo(job.getRedo());
        dbJob.setRunCount(job.getRunCount());
        dbJob.setWarning(job.getWarning());
        dbJob.setTimeout(job.getTimeout());
        if (dbJob.getWarning()) {
            dbJob.setMobiles(job.getMobiles());
            dbJob.setEmailAddress(job.getEmailAddress());
        }
        dbJob.setComment(job.getComment());
        dbJob.setUpdateTime(new Date());
        jobService.merge(dbJob);
        schedulerService.syncJobTigger(dbJob.getJobId(), executeService);
        WebUtils.writeHtml(response, "true");
    }

    @RequestMapping("/editcmd")
    public void editCmd(HttpSession session, HttpServletResponse response, Long jobId, String command) throws SchedulerException {
        command = DigestUtils.passBase64(command);
        Job dbJob = jobService.getJob(jobId);
        if (!jobService.checkJobOwner(session, dbJob.getUserId())) return;
        dbJob.setCommand(command);
        dbJob.setUpdateTime(new Date());
        jobService.merge(dbJob);
        schedulerService.syncJobTigger(Opencron.JobType.FLOW.getCode().equals(dbJob.getJobType()) ? dbJob.getFlowId() : dbJob.getJobId(), executeService);
        WebUtils.writeHtml(response, "true");
    }

    @RequestMapping("/canrun")
    public void canRun(Long id, HttpServletResponse response) {
        WebUtils.writeJson(response, recordService.isRunning(id).toString());
    }

    @RequestMapping("/execute")
    public void remoteExecute(HttpSession session, Long id) {
        JobVo job = jobService.getJobVoById(id);//找到要执行的任务
        if (!jobService.checkJobOwner(session, job.getUserId())) return;
        //手动执行
        Long userId = OpencronTools.getUserId(session);
        job.setUserId(userId);
        job.setExecType(ExecType.OPERATOR.getStatus());
        job.setAgent(agentService.getAgent(job.getAgentId()));
        try {
            this.executeService.executeJob(job);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @RequestMapping("/goexec")
    public String goExec(HttpSession session, Model model) {
        model.addAttribute("agents", agentService.getOwnerAgents(session));
        return "/job/exec";
    }

    @RequestMapping("/batchexec")
    public void batchExec(HttpSession session, String command, String agentIds) {
        if (notEmpty(agentIds) && notEmpty(command)) {
            command = DigestUtils.passBase64(command);
            Long userId = OpencronTools.getUserId(session);
            try {
                this.executeService.batchExecuteJob(userId, command, agentIds);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @RequestMapping("/detail")
    public String showDetail(HttpSession session, Model model, Long id) {
        JobVo jobVo = jobService.getJobVoById(id);
        if (jobVo == null) {
            return "/error/404";
        }
        if (!jobService.checkJobOwner(session, jobVo.getUserId()))
            return "redirect:/job/view?csrf=" + OpencronTools.getCSRF(session);
        model.addAttribute("job", jobVo);
        return "/job/detail";
    }


}
