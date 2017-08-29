/*
 * Copyright 2017 flow.ci
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.flow.platform.api.service.job;

import com.flow.platform.api.dao.JobDao;
import com.flow.platform.api.dao.NodeResultDao;
import com.flow.platform.api.domain.CmdQueueItem;
import com.flow.platform.api.domain.envs.FlowEnvs;
import com.flow.platform.api.domain.job.Job;
import com.flow.platform.api.domain.job.NodeResult;
import com.flow.platform.api.domain.job.NodeStatus;
import com.flow.platform.api.domain.job.NodeTag;
import com.flow.platform.api.domain.node.Node;
import com.flow.platform.api.domain.node.Step;
import com.flow.platform.api.service.node.NodeService;
import com.flow.platform.api.service.node.YmlService;
import com.flow.platform.api.util.CommonUtil;
import com.flow.platform.api.util.EnvUtil;
import com.flow.platform.api.util.NodeUtil;
import com.flow.platform.api.util.PathUtil;
import com.flow.platform.api.util.PlatformURL;
import com.flow.platform.api.util.UrlUtil;
import com.flow.platform.core.exception.FlowException;
import com.flow.platform.core.exception.HttpException;
import com.flow.platform.core.exception.IllegalParameterException;
import com.flow.platform.core.exception.IllegalStatusException;
import com.flow.platform.core.exception.NotFoundException;
import com.flow.platform.core.util.HttpUtil;
import com.flow.platform.domain.Cmd;
import com.flow.platform.domain.CmdBase;
import com.flow.platform.domain.CmdInfo;
import com.flow.platform.domain.CmdResult;
import com.flow.platform.domain.CmdStatus;
import com.flow.platform.domain.CmdType;
import com.flow.platform.domain.Jsonable;
import com.flow.platform.util.Logger;
import com.google.common.base.Strings;
import java.math.BigInteger;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author yh@firim
 */
@Service(value = "jobService")
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class JobServiceImpl implements JobService {

    private static Logger LOGGER = new Logger(JobService.class);

    private Integer RETRY_TIMEs = 5;

    @Autowired
    private JobNodeResultService jobNodeResultService;

    @Autowired
    private JobNodeService jobNodeService;

    @Autowired
    private BlockingQueue<CmdQueueItem> cmdBaseBlockingQueue;

    @Autowired
    private JobDao jobDao;

    @Autowired
    private NodeResultDao nodeResultDao;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private YmlService ymlService;

    @Value(value = "${domain}")
    private String domain;

    @Value(value = "${platform.zone}")
    private String zone;

    @Autowired
    private PlatformURL platformURL;

    @Override
    public Job createJob(String path) {
        Node root = nodeService.find(PathUtil.rootPath(path));
        if (root == null) {
            throw new IllegalParameterException("Path does not existed");
        }

        String status = root.getEnv(FlowEnvs.FLOW_STATUS);
        if (Strings.isNullOrEmpty(status) || !status.equals(FlowEnvs.StatusValue.READY.value())) {
            throw new IllegalStatusException("Cannot create job since status is not READY");
        }

        String yml = null;
        try {
            yml = ymlService.getYmlContent(root.getPath());
            if (Strings.isNullOrEmpty(yml)) {
                throw new IllegalStatusException("Yml is loading for path " + path);
            }
        } catch (FlowException e) {
            LOGGER.error("Fail to get yml content", e);
            throw e;
        }

        // create job
        Job job = new Job(CommonUtil.randomId());
        job.setNodePath(root.getPath());
        job.setNodeName(root.getName());
        job.setNumber(jobDao.maxBuildNumber(job.getNodeName()) + 1);
        job.setEnvs(root.getEnvs());

        //save job
        jobDao.save(job);

        // create yml snapshot for job
        jobNodeService.save(job.getId(), yml);

        // init for node result
        jobNodeResultService.create(job);

        //create session
        createSession(job);

        return job;
    }

    @Override
    public void callback(CmdQueueItem cmdQueueItem) {
        String id = cmdQueueItem.getIdentifier();
        CmdBase cmdBase = cmdQueueItem.getCmdBase();
        Job job;
        if (cmdBase.getType() == CmdType.CREATE_SESSION) {

            // TODO: refactor to find(id, timeout)
            job = find(new BigInteger(id));
            if (job == null) {
                if (cmdQueueItem.getRetryTimes() < RETRY_TIMEs) {
                    try {
                        Thread.sleep(1000);
                        LOGGER.traceMarker("callback", String
                            .format("job not found, retry times - %s jobId - %s", cmdQueueItem.getRetryTimes(), id));
                    } catch (Throwable throwable) {
                    }

                    cmdQueueItem.plus();
                    enterQueue(cmdQueueItem);
                    return;
                }
                LOGGER.warn(String.format("job not found, jobId: %s", id));
                throw new NotFoundException("job not found");
            }
            sessionCallback(job, cmdBase);
        } else if (cmdBase.getType() == CmdType.RUN_SHELL) {
            Map<String, String> map = Jsonable.GSON_CONFIG.fromJson(id, Map.class);
            job = find(new BigInteger(map.get("jobId")));
            nodeCallback(map.get("path"), cmdBase, job);
        } else {
            LOGGER.warn(String.format("not found cmdType, cmdType: %s", cmdBase.getType().toString()));
            throw new NotFoundException("not found cmdType");
        }
    }

    /**
     * run node
     *
     * @param node job node's script and record cmdId and sync send http
     */
    @Override
    public void run(Node node, Job job) {
        if (!NodeUtil.canRun(node)) {
            // run next node
            run(NodeUtil.next(node), job);
            return;
        }

        Node flow = NodeUtil.findRootNode(node);
        EnvUtil.merge(flow, node, false);

        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.RUN_SHELL, node.getScript());
        cmdInfo.setInputs(node.getEnvs());
        cmdInfo.setWebhook(getNodeHook(node, job.getId()));
        cmdInfo.setOutputEnvFilter("FLOW_");
        cmdInfo.setSessionId(job.getSessionId());
        LOGGER.traceMarker("run", String.format("stepName - %s, nodePath - %s", node.getName(), node.getPath()));

        try {
            String res = HttpUtil.post(platformURL.getCmdUrl(), cmdInfo.toJson());

            if (res == null) {
                LOGGER.warn(String
                    .format("post cmd error, cmdUrl: %s, cmdInfo: %s", platformURL.getCmdUrl(), cmdInfo.toJson()));
                throw new HttpException(
                    String.format("Post Cmd Error, Node Name - %s, CmdInfo - %s", node.getName(), cmdInfo.toJson()));
            }

            Cmd cmd = Jsonable.parse(res, Cmd.class);
            NodeResult nodeResult = jobNodeResultService.find(node.getPath(), job.getId());

            // record cmd id
            nodeResult.setCmdId(cmd.getId());
            jobNodeResultService.update(nodeResult);
        } catch (Throwable ignore) {
            LOGGER.warn("run step UnsupportedEncodingException", ignore);
        }
    }

    @Override
    public Job find(BigInteger id) {
        return jobDao.get(id);
    }

    /**
     * get job callback
     */
    private String getJobHook(Job job) {
        return domain + "/hooks/cmd?identifier=" + UrlUtil.urlEncoder(job.getId().toString());
    }

    /**
     * get node callback
     */
    private String getNodeHook(Node node, BigInteger jobId) {
        Map<String, String> map = new HashMap<>();
        map.put("path", node.getPath());
        map.put("jobId", jobId.toString());
        return domain + "/hooks/cmd?identifier=" + UrlUtil.urlEncoder(Jsonable.GSON_CONFIG.toJson(map));
    }

    /**
     * Send create session cmd to create session
     *
     * @throws IllegalStatusException when cannot get Cmd obj from cc
     */
    private void createSession(Job job) {
        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.CREATE_SESSION, null);
        cmdInfo.setWebhook(getJobHook(job));
        LOGGER.traceMarker("createSession", String.format("jobId - %s", job.getId()));

        // create session
        Cmd cmd = sendToQueue(cmdInfo);
        if (cmd == null) {
            throw new IllegalStatusException("Unable to create session since cmd return null");
        }

        job.setCmdId(cmd.getId());
        jobDao.update(job);
    }

    /**
     * delete sessionId
     */
    private void deleteSession(Job job) {
        CmdInfo cmdInfo = new CmdInfo(zone, null, CmdType.DELETE_SESSION, null);
        cmdInfo.setSessionId(job.getSessionId());

        LOGGER.traceMarker("deleteSession", String.format("sessionId - %s", job.getSessionId()));
        // delete session
        sendToQueue(cmdInfo);
    }

    /**
     * send cmd by queue
     */
    private Cmd sendToQueue(CmdInfo cmdInfo) {
        Cmd cmd = null;
        StringBuilder stringBuilder = new StringBuilder(platformURL.getQueueUrl());
        stringBuilder.append("?priority=1&retry=5");
        try {
            String res = HttpUtil.post(stringBuilder.toString(), cmdInfo.toJson());

            if (res == null) {
                String message = String
                    .format("post session to queue error, cmdUrl: %s, cmdInfo: %s", stringBuilder.toString(),
                        cmdInfo.toJson());

                LOGGER.warn(message);
                throw new HttpException(message);
            }

            cmd = Jsonable.parse(res, Cmd.class);
        } catch (Throwable ignore) {
            LOGGER.warn("run step UnsupportedEncodingException", ignore);
        }
        return cmd;
    }

    /**
     * session success callback
     */
    private void sessionCallback(Job job, CmdBase cmdBase) {
        if (cmdBase.getStatus() == CmdStatus.SENT) {
            job.setUpdatedAt(ZonedDateTime.now());
            job.setSessionId(cmdBase.getSessionId());
            jobDao.update(job);

            // run step
            NodeResult nodeResult = jobNodeResultService.find(job.getNodePath(), job.getId());
            Node flow = jobNodeService.get(job.getId(), nodeResult.getNodeResultKey().getPath());

            if (flow == null) {
                throw new NotFoundException(String.format("Not Found Job Flow - %s", flow.getName()));
            }

            // start run flow
            run(NodeUtil.first(flow), job);
        } else {
            LOGGER.warn(String.format("Create Session Error Session Status - %s", cmdBase.getStatus().getName()));
        }
    }

    /**
     * step success callback
     */
    private void nodeCallback(String nodePath, CmdBase cmdBase, Job job) {
        NodeResult nodeResult = jobNodeResultService.find(nodePath, job.getId());
        NodeStatus nodeStatus = handleStatus(cmdBase);

        // keep job step status sorted
        if (nodeResult.getStatus().getLevel() >= nodeStatus.getLevel()) {
            return;
        }

        //update job step status
        nodeResult.setStatus(nodeStatus);

        jobNodeResultService.update(nodeResult);

        Node step = jobNodeService.get(job.getId(), nodeResult.getNodeResultKey().getPath());
        //update node status
        updateNodeStatus(step, cmdBase, job);
    }

    /**
     * update job flow status
     */
    private void updateJobStatus(NodeResult nodeResult) {
        Node node = jobNodeService
            .get(nodeResult.getNodeResultKey().getJobId(), nodeResult.getNodeResultKey().getPath());
        Job job = find(nodeResult.getNodeResultKey().getJobId());

        if (node instanceof Step) {
            return;
        }

        NodeStatus nodeStatus = nodeResult.getStatus();

        if (nodeStatus == NodeStatus.TIMEOUT || nodeStatus == NodeStatus.FAILURE) {
            nodeStatus = NodeStatus.FAILURE;
        }

        //delete session
        if (nodeStatus == NodeStatus.FAILURE || nodeStatus == NodeStatus.SUCCESS) {
            deleteSession(job);
        }
    }

    /**
     * update node status
     */
    private void updateNodeStatus(Node node, CmdBase cmdBase, Job job) {
        NodeResult nodeResult = jobNodeResultService.find(node.getPath(), job.getId());
        //update jobNode
        nodeResult.setUpdatedAt(ZonedDateTime.now());
        nodeResult.setStatus(handleStatus(cmdBase));
        CmdResult cmdResult = ((Cmd) cmdBase).getCmdResult();

        Node parent = node.getParent();
        Node prev = node.getPrev();
        Node next = node.getNext();
        switch (nodeResult.getStatus()) {
            case PENDING:
            case RUNNING:
                if (cmdResult != null) {
                    nodeResult.setStartTime(cmdResult.getStartTime());
                }

                if (parent != null) {
                    // first node running update parent node running
                    if (prev == null) {
                        updateNodeStatus(node.getParent(), cmdBase, job);
                    }
                }
                break;
            case SUCCESS:
                if (cmdResult != null) {
                    nodeResult.setFinishTime(cmdResult.getFinishTime());
                }

                if (parent != null) {
                    // last node running update parent node running
                    if (next == null) {
                        updateNodeStatus(node.getParent(), cmdBase, job);
                    } else {
                        run(NodeUtil.next(node), job);
                    }
                }
                break;
            case TIMEOUT:
            case FAILURE:
                if (cmdResult != null) {
                    nodeResult.setFinishTime(cmdResult.getFinishTime());
                }

                //update parent node if next is not null, if allow failure is false
                if (parent != null && (((Step) node).getAllowFailure())) {
                    if (next == null) {
                        updateNodeStatus(node.getParent(), cmdBase, job);
                    }
                }

                //update parent node if next is not null, if allow failure is false
                if (parent != null && !((Step) node).getAllowFailure()) {
                    updateNodeStatus(node.getParent(), cmdBase, job);
                }

                //next node not null, run next node
                if (next != null && ((Step) node).getAllowFailure()) {
                    run(NodeUtil.next(node), job);
                }
                break;
        }

        //update job status
        updateJobStatus(nodeResult);

        //update node info
        updateNodeInfo(node, cmdBase, job);

        //save
        jobNodeResultService.update(nodeResult);
    }

    /**
     * update node info outputs duration start time
     */
    private void updateNodeInfo(Node node, CmdBase cmdBase, Job job) {
        NodeResult nodeResult = jobNodeResultService.find(node.getPath(), job.getId());
        //update jobNode
        nodeResult.setUpdatedAt(ZonedDateTime.now());

        CmdResult cmdResult = ((Cmd) cmdBase).getCmdResult();

        if (cmdResult != null) {
            nodeResult.setExitCode(cmdResult.getExitValue());
            if (NodeUtil.canRun(node)) {
                nodeResult.setLogPaths(((Cmd) cmdBase).getLogPaths());
            }

            // setting start time
            if (nodeResult.getStartTime() == null) {
                nodeResult.setStartTime(cmdResult.getStartTime());
            }

            // setting finish time
            if (((Cmd) cmdBase).getFinishedDate() != null) {
                nodeResult.setFinishTime(((Cmd) cmdBase).getFinishedDate());
            }

            //setting duration from endTime - startTime
            if (nodeResult.getFinishTime() != null) {
                Long duration =
                    nodeResult.getFinishTime().toEpochSecond() - nodeResult.getStartTime().toEpochSecond();
                nodeResult.setDuration(duration);
            }

            // merge envs
            EnvUtil.merge(cmdResult.getOutput(), nodeResult.getOutputs(), false);

            if (node.getParent() != null) {
                updateNodeInfo(node.getParent(), cmdBase, job);
            }

            //save
            jobNodeResultService.update(nodeResult);
        }
    }

    /**
     * transfer cmdStatus to Job status
     */
    private NodeStatus handleStatus(CmdBase cmdBase) {
        NodeStatus nodeStatus = null;
        switch (cmdBase.getStatus()) {
            case SENT:
            case PENDING:
                nodeStatus = NodeStatus.PENDING;
                break;
            case RUNNING:
            case EXECUTED:
                nodeStatus = NodeStatus.RUNNING;
                break;
            case LOGGED:
                CmdResult cmdResult = ((Cmd) cmdBase).getCmdResult();
                if (cmdResult != null && cmdResult.getExitValue() == 0) {
                    nodeStatus = NodeStatus.SUCCESS;
                } else {
                    nodeStatus = NodeStatus.FAILURE;
                }
                break;
            case KILLED:
            case EXCEPTION:
            case REJECTED:
                nodeStatus = NodeStatus.FAILURE;
                break;
            case STOPPED:
                nodeStatus = NodeStatus.STOPPED;
                break;
            case TIMEOUT_KILL:
                nodeStatus = NodeStatus.TIMEOUT;
                break;
        }
        return nodeStatus;
    }

    @Override
    public List<Job> listJobs(String flowName, List<String> flowNames) {
        if (flowName == null && flowNames == null) {
            return jobDao.list();
        }

        if (flowNames != null) {
            return jobDao.listLatest(flowNames);
        }

        if (flowName != null) {
            return jobDao.list(flowName);
        }
        return null;
    }

    @Override
    public Job find(String flowName, Integer number) {
        Job job = jobDao.get(flowName, number);
        if (job == null) {
            throw new NotFoundException("job is not found");
        }
        return job;
    }

    @Override
    public void enterQueue(CmdQueueItem cmdQueueItem) {
        try {
            cmdBaseBlockingQueue.put(cmdQueueItem);
        } catch (Throwable throwable) {
            LOGGER.warnMarker("enterQueue", String.format("exception - %s", throwable));
        }
    }

    @Override
    public Job stopJob(String name, Integer buildNumber) {
        String cmdId;
        Job runningJob = find(name, buildNumber);

        if (runningJob == null) {
            throw new NotFoundException(String.format("running job not found name - %s", name));
        }

        if (runningJob.getResult() == null) {
            throw new NotFoundException(String.format("running job not found node result - %s", name));
        }

        //job in create session status
        if (runningJob.getResult().getStatus() == NodeStatus.ENQUEUE
            || runningJob.getResult().getStatus() == NodeStatus.PENDING) {
            cmdId = runningJob.getCmdId();

            // job finish, stop job failure
        } else if (runningJob.getResult().getStatus() == NodeStatus.SUCCESS
            || runningJob.getResult().getStatus() == NodeStatus.FAILURE) {
            throw new IllegalParameterException("can not stop, job finish");

        } else { // running
            NodeResult runningNodeResult = nodeResultDao.get(runningJob.getId(), NodeStatus.RUNNING, NodeTag.STEP);
            cmdId = runningNodeResult.getCmdId();
        }

        String url = new StringBuilder(platformURL.getCmdStopUrl()).append(cmdId).toString();
        LOGGER.traceMarker("stopJob", String.format("url - %s", url));

        updateNodeResult(runningJob, NodeStatus.STOPPED);

        try {
            HttpUtil.post(url, "");
        } catch (Throwable throwable) {
            LOGGER.traceMarker("stopJob", String.format("stop job error - %s", throwable));
            throw new IllegalParameterException(String.format("stop job error - %s", throwable));
        }
        return runningJob;
    }

    private void updateNodeResult(Job job, NodeStatus status) {
        List<NodeResult> results = jobNodeResultService.list(job);
        for (NodeResult result : results) {
            if (result.getStatus() != NodeStatus.SUCCESS) {
                result.setStatus(status);
                jobNodeResultService.update(result);

                if (job.getNodePath().equals(result.getPath())) {
                    job.setResult(result);
                }
            }
        }
    }

    @Override
    public List<NodeResult> listNodeResult(String flowName, Integer number) {
        Job job = find(flowName, number);
        return jobNodeResultService.list(job);
    }
}
