package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.conf.XxlJobAdminConfig;
import com.xxl.job.admin.core.cron.CronExpression;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author xuxueli 2019-05-21
 */
public class JobScheduleHelper {
    private static Logger logger = LoggerFactory.getLogger(JobScheduleHelper.class);

    private static JobScheduleHelper instance = new JobScheduleHelper();
    public static JobScheduleHelper getInstance(){
        return instance;
    }

    public static final long PRE_READ_MS = 5000;    // pre read

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean scheduleThreadToStop = false;
    private volatile boolean ringThreadToStop = false;
    // key: 秒 value: 任务的ID
    private volatile static Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();

    public void start(){

        // schedule thread
        // 任务调度线程
        // 1: 任务过期5s，那么直接丢弃，用当前时间计算下次应该触发的时间
        // 2: 过期还未到5s，那么直接调度一次，然后看下次执行的时间是否在5s内，如果是，那么扔进执行队列，执行队列判断执行
        // 3: 直接扔进队列，修改数据库下次执行的时间
        // 这个线程主要是执行数据库交互，然后预热任务，将任务扔进具体的执行队列
        // 注意：这里的队列其实指的map，不过队列会比较形象 key: 执行的秒 value: jobId
        scheduleThread = new Thread(new Runnable() {
            @Override
            public void run() {

                try {
                    TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis()%1000 );
                } catch (InterruptedException e) {
                    if (!scheduleThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                logger.info(">>>>>>>>> init xxl-job admin scheduler success.");

                Connection conn = null;
                while (!scheduleThreadToStop) {

                    // 扫描任务
                    long start = System.currentTimeMillis();
                    PreparedStatement preparedStatement = null;
                    try {
                        if (conn==null || conn.isClosed()) {
                            conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                        }
                        conn.setAutoCommit(false);

                        // 数据库排他锁，保证调度在多机环境下只有一个在调度
                        preparedStatement = conn.prepareStatement(  "select * from xxl_job_lock where lock_name = 'schedule_lock' for update" );
                        preparedStatement.execute();

                        // tx start

                        long nowTime = System.currentTimeMillis();
                        // 1、预读5s内调度任务
                        List<XxlJobInfo> scheduleList = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleJobQuery(nowTime + PRE_READ_MS);
                        if (scheduleList!=null && scheduleList.size()>0) {
                            // 2、推送时间轮
                            for (XxlJobInfo jobInfo: scheduleList) {

                                // 时间轮刻度计算
                                // 这里任务已经过期了，超时了，小于了当前时间+5s
                                if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
                                    // 过期超5s：本地忽略，当前时间开始计算下次触发时间

                                    // fresh next
                                    // 直接为job设置下次有效触发时间
                                    jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
                                    jobInfo.setTriggerNextTime(
                                            new CronExpression(jobInfo.getJobCron())
                                                    .getNextValidTimeAfter(new Date())
                                                    .getTime()
                                    );

                                } else if (nowTime > jobInfo.getTriggerNextTime()) {
                                    // 过期5s内 ：立即触发一次，当前时间开始计算下次触发时间；

                                    CronExpression cronExpression = new CronExpression(jobInfo.getJobCron());
                                    long nextTime = cronExpression.getNextValidTimeAfter(new Date()).getTime();

                                    // 1、trigger
                                    // 调度线程直接触发
                                    JobTriggerPoolHelper.trigger(jobInfo.getId(), TriggerTypeEnum.CRON, -1, null, null);
                                    logger.debug(">>>>>>>>>>> xxl-job, shecule push trigger : jobId = " + jobInfo.getId() );

                                    // 2、fresh next
                                    jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
                                    jobInfo.setTriggerNextTime(nextTime);


                                    // 下次触发的任务时间点在5秒内
                                    if (jobInfo.getTriggerNextTime() - nowTime < PRE_READ_MS) {

                                        // 1、make ring second
                                        // 获取下次任务执行时间的秒
                                        int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                        // 2、push time ring
                                        // 将下次执行的秒为key，jobId为value存在map中
                                        pushTimeRing(ringSecond, jobInfo.getId());

                                        // 3、fresh next
                                        // 相当于是计算"下下次"的，因为"下一次"已经被推到队列等待执行了。
                                        jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
                                        jobInfo.setTriggerNextTime(
                                                new CronExpression(jobInfo.getJobCron())
                                                        .getNextValidTimeAfter(new Date(jobInfo.getTriggerNextTime()))
                                                        .getTime()
                                        );

                                    }

                                } else {
                                    // 未过期：正常触发，递增计算下次触发时间

                                    // 1、make ring second
                                    int ringSecond = (int)((jobInfo.getTriggerNextTime()/1000)%60);

                                    // 2、push time ring
                                    pushTimeRing(ringSecond, jobInfo.getId());

                                    // 3、fresh next
                                    jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
                                    jobInfo.setTriggerNextTime(
                                            new CronExpression(jobInfo.getJobCron())
                                                    .getNextValidTimeAfter(new Date(jobInfo.getTriggerNextTime()))
                                                    .getTime()
                                    );

                                }

                            }

                            // 3、更新trigger信息
                            for (XxlJobInfo jobInfo: scheduleList) {
                                XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleUpdate(jobInfo);
                            }

                        }

                        // tx stop


                    } catch (Exception e) {
                        if (!scheduleThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread error:{}", e);
                        }
                    } finally {

                        // commit
                        try {
                            conn.commit();
                        } catch (SQLException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }

                        // close PreparedStatement
                        if (null != preparedStatement) {
                            try {
                                preparedStatement.close();
                            } catch (SQLException ignore) {
                                if (!scheduleThreadToStop) {
                                    logger.error(ignore.getMessage(), ignore);
                                }
                            }
                        }
                    }
                    long cost = System.currentTimeMillis()-start;

                    // next second, align second
                    try {
                        if (cost < 1000) {
                            TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis()%1000);
                        }
                    } catch (InterruptedException e) {
                        if (!scheduleThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }

                }
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e) {
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread stop");
            }
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("xxl-job, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();


        // ring thread
        // 从队列中获取任务，进行触发
        // 1: 根据当前秒数，拿出当前秒应该执行的任务，直接调度
        // warn: 有个问题就是，如果这秒任务太多的话，执行超过两秒，就必须等60秒，才能再次调度到，
        // 但是这种情况几乎很难出现，因为调度有专门的调度线程
        ringThread = new Thread(new Runnable() {
            @Override
            public void run() {

                // align second
                try {
                    // 休眠到下一秒
                    TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis()%1000 );
                } catch (InterruptedException e) {
                    if (!ringThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }

                while (!ringThreadToStop) {

                    try {
                        // second data
                        List<Integer> ringItemData = new ArrayList<>();
                        int nowSecond = Calendar.getInstance().get(Calendar.SECOND);   // 避免处理耗时太长，跨过刻度，向前校验一个刻度；
                        // 将当前秒以及前一秒的任务拿出来进行调度
                        for (int i = 0; i < 2; i++) {
                            List<Integer> tmpData = ringData.remove( (nowSecond+60-i)%60 );
                            if (tmpData != null) {
                                ringItemData.addAll(tmpData);
                            }
                        }

                        // ring trigger
                        logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Arrays.asList(ringItemData) );
                        if (ringItemData!=null && ringItemData.size()>0) {
                            // do trigger
                            for (int jobId: ringItemData) {
                                // do trigger
                                JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null);
                            }
                            // clear
                            ringItemData.clear();
                        }
                    } catch (Exception e) {
                        if (!ringThreadToStop) {
                            logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                        }
                    }

                    // next second, align second
                    try {
                        // 睡眠到下一秒
                        TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis()%1000);
                    } catch (InterruptedException e) {
                        if (!ringThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
                logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
            }
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }

    private void pushTimeRing(int ringSecond, int jobId){
        // push async ring
        List<Integer> ringItemData = ringData.get(ringSecond);
        if (ringItemData == null) {
            ringItemData = new ArrayList<Integer>();
            ringData.put(ringSecond, ringItemData);
        }
        ringItemData.add(jobId);

        logger.debug(">>>>>>>>>>> xxl-job, shecule push time-ring : " + ringSecond + " = " + Arrays.asList(ringItemData) );
    }

    public void toStop(){

        // 1、stop schedule
        scheduleThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);  // wait
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (scheduleThread.getState() != Thread.State.TERMINATED){
            // interrupt and wait
            scheduleThread.interrupt();
            try {
                scheduleThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // if has ring data
        boolean hasRingData = false;
        if (!ringData.isEmpty()) {
            for (int second : ringData.keySet()) {
                List<Integer> tmpData = ringData.get(second);
                if (tmpData!=null && tmpData.size()>0) {
                    hasRingData = true;
                    break;
                }
            }
        }
        if (hasRingData) {
            try {
                TimeUnit.SECONDS.sleep(8);
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // stop ring (wait job-in-memory stop)
        ringThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (ringThread.getState() != Thread.State.TERMINATED){
            // interrupt and wait
            ringThread.interrupt();
            try {
                ringThread.join();
            } catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper stop");
    }

}
