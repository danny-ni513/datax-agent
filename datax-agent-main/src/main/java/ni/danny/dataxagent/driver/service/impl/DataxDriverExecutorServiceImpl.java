package ni.danny.dataxagent.driver.service.impl;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import ni.danny.dataxagent.constant.ZookeeperConstant;
import ni.danny.dataxagent.driver.dto.ExecutorThreadDTO;
import ni.danny.dataxagent.driver.dto.event.DriverEventDTO;
import ni.danny.dataxagent.driver.dto.event.DriverExecutorEventDTO;
import ni.danny.dataxagent.driver.enums.DriverExecutorEventTypeEnum;
import ni.danny.dataxagent.driver.enums.DriverJobEventTypeEnum;
import ni.danny.dataxagent.driver.enums.ExecutorThreadStatusEnums;
import ni.danny.dataxagent.driver.producer.DriverExecutorEventProducerWithTranslator;
import ni.danny.dataxagent.driver.service.DataxDriverExecutorService;
import ni.danny.dataxagent.driver.service.DataxDriverService;
import ni.danny.dataxagent.enums.ExecutorTaskStatusEnum;
import ni.danny.dataxagent.service.DataxAgentService;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class DataxDriverExecutorServiceImpl implements DataxDriverExecutorService {

    @Autowired
    private CuratorFramework zookeeperDriverClient;


    @Autowired
    @Lazy
    private DataxDriverService dataxDriverService;

    @Value("${datax.executor.pool.maxPoolSize}")
    private int executorMaxPoolSize;


    @Override
    public void scanExecutor() {
        ZookeeperConstant.updateDriverExecutorEventHandlerStatus(ZookeeperConstant.STATUS_SLEEP);
        ZookeeperConstant.idleThreadSet.clear();
        Set<ExecutorThreadDTO> tmpSet = new HashSet<>();
        try{
            List<String> list = zookeeperDriverClient.getChildren().forPath(ZookeeperConstant.EXECUTOR_ROOT_PATH);
            List<String> jobExecutorList = zookeeperDriverClient.getChildren().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH);
            for (String executor : jobExecutorList) {
                List<String> threads = zookeeperDriverClient.getChildren().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+executor);
                    if(threads!=null&&!threads.isEmpty()){
                        for(String thread:threads){
                            List<String> threadTasks = zookeeperDriverClient.getChildren().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                                    + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + executor + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + thread);
                            if (threadTasks == null || threadTasks.isEmpty()) {
                                if(list.contains(executor)){
                                    tmpSet.add(new ExecutorThreadDTO(executor, Integer.parseInt(thread)));
                                }else{
                                    zookeeperDriverClient.delete().guaranteed().deletingChildrenIfNeeded().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                                            + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + executor + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + thread);
                                }
                            }else{
                                if(list.contains(executor)){
                                    zookeeperDriverClient.setData().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                                            + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + executor
                                            + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + thread,ExecutorThreadStatusEnums.READY.toString().getBytes());
                                    tmpSet.add(new ExecutorThreadDTO(executor, Integer.parseInt(thread)));
                                }else{
                                    zookeeperDriverClient.setData().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                                            + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + executor
                                            + ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG + thread,ExecutorThreadStatusEnums.WAITRECYCLE.toString().getBytes());
                                }
                            }
                        }
                    }

            }

            ZookeeperConstant.idleThreadSet.addAll(tmpSet);
            dataxDriverService.dispatchEvent(new DriverEventDTO(DriverJobEventTypeEnum.TASK_DISPATCH));
        }catch (Exception ex){
            dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.EXECUTOR_SCAN,null,0,null));
        }
        finally {
            ZookeeperConstant.updateDriverExecutorEventHandlerStatus(ZookeeperConstant.STATUS_RUNNING);
        }

    }

    @Override
    public void dispatchJobExecutorEvent(CuratorCacheListener.Type type, ChildData oldData, ChildData data) {
        String[] pathInfo = null;
        String pathStr = "";
        if(data!=null){
            pathStr = data.getPath();
        }else if(oldData != null){
            pathStr = oldData.getPath();
        }
        pathInfo =pathStr.split(ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG);
        log.debug("type==[{}], pathInfo ==[{}]",type,pathStr);
        switch (type.toString()){
            case "NODE_CREATED":
                if(pathInfo.length==5){
                    //dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_CREATED,pathInfo[3],Integer.parseInt(pathInfo[4]),null));
                }else if(pathInfo.length==6){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_TASK_CREATED,pathInfo[3],Integer.parseInt(pathInfo[4]),pathInfo[5]));
                }
                break;
            case "NODE_CHANGED":
                if(pathInfo.length==5){
                    if(ExecutorThreadStatusEnums.WAITRECYCLE.toString().equals(new String(data.getData()))){
                        dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_WAITRECYCLE
                                ,pathInfo[3],Integer.parseInt(pathInfo[4]),null) );
                    }else if(ExecutorThreadStatusEnums.READY.toString().equals(new String(data.getData()))){
                        dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_READY
                                ,pathInfo[3],Integer.parseInt(pathInfo[4]),null) );
                    }
                }else if(pathInfo.length==6){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_TASK_SET_TRACEID
                            ,pathInfo[3],Integer.parseInt(pathInfo[4]),pathInfo[5]) );
                }
                break;
            case "NODE_DELETED":
                if(pathInfo.length==5){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_REMOVED,pathInfo[3],Integer.parseInt(pathInfo[4]),null) );
                }else if(pathInfo.length==6){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_TASK_REMOVED,pathInfo[3],Integer.parseInt(pathInfo[4]),pathInfo[5]));
                }
                break;
            default:break;
        }
    }

    @Override
    public void dispatchExecutorEvent(CuratorCacheListener.Type type, ChildData oldData, ChildData data) {
        String pathStr = "";
        String[] pathInfo = null;
        if(data!=null){
            pathStr = data.getPath();
        }else if(oldData != null){
            pathStr = oldData.getPath();
        }
        pathInfo = pathStr.split(ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG);
        log.debug("type==[{}], pathInfo ==[{}]",type,pathStr);
        switch (type.toString()){
            case "NODE_CREATED":
                if(pathInfo.length==3){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.EXECUTOR_UP,pathInfo[2],0,null));
                }
                break;
            case "NODE_DELETED":
                if(pathInfo.length==3){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.EXECUTOR_DOWN,pathInfo[2],0,null));
                }
                break;
            default:break;
        }
    }

    @Override
    public void executorCreatedEvent(DriverExecutorEventDTO eventDTO) {
        try{
            String jobExecutor = ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor();
            Stat jobExecutorStat = zookeeperDriverClient.checkExists().forPath(jobExecutor);
            if(jobExecutorStat == null){
                zookeeperDriverClient.create().withMode(CreateMode.PERSISTENT).forPath(jobExecutor,eventDTO.getExecutor().getBytes());
            }

            List<ExecutorThreadDTO> list = new ArrayList<>();
                for(int i=0;i<executorMaxPoolSize;i++){
                    Stat threadStat = zookeeperDriverClient.checkExists().forPath(jobExecutor+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+i);
                    if(threadStat==null){
                        zookeeperDriverClient.create().withMode(CreateMode.PERSISTENT)
                                .forPath(jobExecutor+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+i,ExecutorThreadStatusEnums.READY.toString().getBytes());
                    }else{
                        zookeeperDriverClient.setData()
                                .forPath(jobExecutor+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+i,ExecutorThreadStatusEnums.READY.toString().getBytes());
                    }
                    list.add(new ExecutorThreadDTO(eventDTO.getExecutor(),i));
                }
                for(ExecutorThreadDTO dto:list){
                    dataxDriverService.dispatchExecutorEvent(new DriverExecutorEventDTO(DriverExecutorEventTypeEnum.THREAD_CREATED,dto.getExecutor(),dto.getThread(),null) );
                }
        }catch (Exception ex){
            eventDTO.setDelay(2*1000);
            dataxDriverService.dispatchExecutorEvent(eventDTO);
        }
    }

    @Override
    public void executorRemovedEvent(DriverExecutorEventDTO eventDTO) {
        try{
            String jobExecutor = ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor();
            Stat jobExecutorStat = zookeeperDriverClient.checkExists().forPath(jobExecutor);
            if(jobExecutorStat!=null){
                List<String> threads = zookeeperDriverClient.getChildren().forPath(jobExecutor);
                if(threads==null||threads.isEmpty()){
                    zookeeperDriverClient.delete().guaranteed().deletingChildrenIfNeeded().forPath(jobExecutor);
                    return;
                }

                for (String thread:threads){
                    List<String> threadTasks = zookeeperDriverClient.getChildren().forPath(jobExecutor
                            +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+thread);
                    if(threadTasks==null||threadTasks.isEmpty()){
                        zookeeperDriverClient.delete().guaranteed().deletingChildrenIfNeeded().forPath(jobExecutor
                                +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+thread);
                    }else{
                        zookeeperDriverClient.setData().forPath(jobExecutor
                                +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+thread
                                ,ExecutorThreadStatusEnums.WAITRECYCLE.toString().getBytes());
                    }
                }
            }
        }catch (Exception ex){
            eventDTO.setDelay(2*1000);
            dataxDriverService.dispatchExecutorEvent(eventDTO);
        }
    }

    @Override
    public void threadCreatedEvent(DriverExecutorEventDTO eventDTO) {
        log.debug("====> thread created [{}]",eventDTO);
        dataxDriverService.addHandlerResource(new ExecutorThreadDTO(eventDTO.getExecutor(),eventDTO.getThread()),null,null);
    }

    @Override
    public void threadUpdateWaitRecycleEvent(DriverExecutorEventDTO eventDTO) {
        try{
            String threadPath = ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor()
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getThread();
            List<String> threadTasks = zookeeperDriverClient.getChildren().forPath(threadPath);
            if(threadTasks==null||threadTasks.isEmpty()){
                zookeeperDriverClient.delete().guaranteed().forPath(threadPath);
                return;
            }
            for(String task:threadTasks){
                String[] taskInfo = task.split(ZookeeperConstant.JOB_TASK_SPLIT_TAG);
                if(taskInfo.length==2){
                    String checkPath = ZookeeperConstant.JOB_LIST_ROOT_PATH+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG
                            +taskInfo[0]+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+taskInfo[1];
                            checkAndRemoveWaitRecycle(threadPath,checkPath);
                }else{
                    zookeeperDriverClient.delete().guaranteed().forPath(threadPath+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+task);
                }
            }
        }catch (Exception ex){
            eventDTO.setDelay(2*1000);
            dataxDriverService.dispatchExecutorEvent(eventDTO);
        }
    }

    private void checkAndRemoveWaitRecycle(String delPath,String path)throws Exception{
        Stat stat = zookeeperDriverClient.checkExists().forPath(path);
        if(stat == null){
            zookeeperDriverClient.delete().guaranteed().deletingChildrenIfNeeded()
                    .forPath(delPath);
            return ;
        }
        List<String> list = zookeeperDriverClient.getChildren().forPath(path);
        if(list==null||list.isEmpty()){
            zookeeperDriverClient.delete().guaranteed().deletingChildrenIfNeeded()
                    .forPath(delPath);
            return ;
        }

        String status = new String(zookeeperDriverClient.getData().forPath(path));
        if(ExecutorTaskStatusEnum.REJECT.getValue().equals(status)||ExecutorTaskStatusEnum.FINISH.getValue().equals(status)){
            zookeeperDriverClient.delete().guaranteed().deletingChildrenIfNeeded()
                    .forPath(delPath);
            return;
        }
        for(String tPath:list){
            checkAndRemoveWaitRecycle(delPath,path+ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+tPath);
        }
    }

    @Override
    public void threadUpdateReadyEvent(DriverExecutorEventDTO eventDTO) {
        try{
            List<String> tasks = zookeeperDriverClient.getChildren().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor()
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getThread());
            if(tasks==null||tasks.isEmpty()){
                dataxDriverService.addHandlerResource(new ExecutorThreadDTO(eventDTO.getExecutor(),eventDTO.getThread()),null,null);
            }
        }catch (Exception ex){
            eventDTO.setDelay(2*1000);
            dataxDriverService.dispatchExecutorEvent(eventDTO);
        }
    }

    @Override
    public void threadRemovedEvent(DriverExecutorEventDTO eventDTO) {
        try{
            List<String> threads = zookeeperDriverClient.getChildren().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor());
            if(threads==null||threads.isEmpty()){
                zookeeperDriverClient.delete().guaranteed().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor());
            }
        }catch (Exception ex){
            eventDTO.setDelay(2*1000);
            dataxDriverService.dispatchExecutorEvent(eventDTO);
        }
    }

    @Override
    public void threadTaskCreatedEvent(DriverExecutorEventDTO eventDTO) {
       //doNothing
    }

    @Override
    public void threadTaskUpdatedEvent(DriverExecutorEventDTO eventDTO) {
        //doNothing
    }

    @Override
    public void threadTaskRemovedEvent(DriverExecutorEventDTO eventDTO) {
        try{
            String status = new String(zookeeperDriverClient.getData().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor()
                    +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getThread()));

            if(ExecutorThreadStatusEnums.WAITRECYCLE.toString().equals(status)){
                zookeeperDriverClient.delete().guaranteed().forPath(ZookeeperConstant.JOB_EXECUTOR_ROOT_PATH
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor()
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getThread());
                return;
            }
            String[] taskInfo = eventDTO.getJobTask().split(ZookeeperConstant.JOB_TASK_SPLIT_TAG);
            if(taskInfo.length==2){
                Stat taskThreadStat = zookeeperDriverClient.checkExists().forPath(ZookeeperConstant.JOB_LIST_ROOT_PATH
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+taskInfo[0]
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+taskInfo[1]
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor()
                        +ZookeeperConstant.JOB_TASK_SPLIT_TAG+eventDTO.getThread());
                if(taskThreadStat==null){
                    dataxDriverService.addHandlerResource(new ExecutorThreadDTO(eventDTO.getExecutor(),eventDTO.getThread()),null,null);
                    return;
                }

                String taskThreadStatus = new String(zookeeperDriverClient.getData().forPath(ZookeeperConstant.JOB_LIST_ROOT_PATH
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+taskInfo[0]
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+taskInfo[1]
                        +ZookeeperConstant.ZOOKEEPER_PATH_SPLIT_TAG+eventDTO.getExecutor()
                        +ZookeeperConstant.JOB_TASK_SPLIT_TAG+eventDTO.getThread()));

                if(ExecutorTaskStatusEnum.REJECT.getValue().equals(taskThreadStatus)||ExecutorTaskStatusEnum.FINISH.getValue().equals(taskThreadStatus)){
                    dataxDriverService.addHandlerResource(new ExecutorThreadDTO(eventDTO.getExecutor(),eventDTO.getThread()),null,null);
                }
            }
        }catch (Exception ex){
            log.error("{}",ex);
            eventDTO.setDelay(2*1000);
            dataxDriverService.dispatchExecutorEvent(eventDTO);
        }
    }

}
