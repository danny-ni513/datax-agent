package ni.danny.dataxagent.constant;

import ni.danny.dataxagent.dto.ZookeeperEventDTO;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.DelayQueue;

public class ZookeeperConstant {
    public final static String NAME_SPACE = "datax-agent";
    public final static String DRIVER_PATH = "/driver";
    public final static String EXECUTOR_ROOT_PATH = "/executor";
    public final static String JOB_ROOT_PATH = "/job";
    /** 任务目录下所有节点均为永久节点 */
    public final static String JOB_EXECUTOR_ROOT_PATH = "/job/executor";

    public final static String JOB_LIST_ROOT_PATH = "/job/list";

    public final static String JOB_TASK_SPLIT_TAG = "-";

    public final static String ZOOKEEPER_PATH_SPLIT_TAG = "/";

    public final static String HTTP_PROTOCOL_TAG = "http://";

    public static String driverName = null ;


    public final static DelayQueue<ZookeeperEventDTO> executorEventList = new DelayQueue<>();


    /** executor/threadId  **/
    public final static ConcurrentSkipListSet<String> idleExecutorThreadSet = new ConcurrentSkipListSet<>();

    /** jobId-taskId **/
    public final static ConcurrentSkipListSet<String> waitForExecutorJobTaskSet = new ConcurrentSkipListSet<>();




    public static String executorStatus = "INIT";

    public static String STATUS_INIT="INIT";

    public static String STATUS_RUNNING = "RUNNING";

    public static String STATUS_SLEEP = "SLEEP";

    public static String STATUS_WATCH = "WATCH";

    public static String driverJobEventHandlerStatus = STATUS_RUNNING;

    public static synchronized void updateDriverJobEventHandlerStatus(String newStatus){
        driverJobEventHandlerStatus = newStatus;
    }


    public static synchronized String updateExecutorStatus(String oldStatus,String newStatus){
        if(oldStatus==null||oldStatus.equals(executorStatus)){
            executorStatus = newStatus;
            return executorStatus;
        }else{
            return executorStatus;
        }
    }

    public static synchronized String updateDriverName(String oldName,String name){
        if(oldName==null || oldName.equals(driverName)){
            driverName = name;
            return driverName;
        }else{
            return driverName;
        }
    }

}
