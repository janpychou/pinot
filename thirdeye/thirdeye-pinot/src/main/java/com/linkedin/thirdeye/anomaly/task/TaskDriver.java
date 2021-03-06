package com.linkedin.thirdeye.anomaly.task;

import com.linkedin.thirdeye.db.dao.AnomalyJobDAO;
import com.linkedin.thirdeye.db.dao.AnomalyResultDAO;
import com.linkedin.thirdeye.db.dao.AnomalyTaskDAO;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.persistence.OptimisticLockException;
import javax.persistence.RollbackException;

import org.hibernate.StaleObjectStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linkedin.thirdeye.anomaly.ThirdEyeAnomalyConfiguration;
import com.linkedin.thirdeye.anomaly.task.TaskConstants.TaskStatus;
import com.linkedin.thirdeye.anomaly.task.TaskConstants.TaskType;
import com.linkedin.thirdeye.db.entity.AnomalyTaskSpec;
import com.linkedin.thirdeye.detector.function.AnomalyFunctionFactory;

public class TaskDriver {

  private static final Logger LOG = LoggerFactory.getLogger(TaskDriver.class);

  private ExecutorService taskExecutorService;

  private AnomalyJobDAO anomalyJobDAO;
  private AnomalyTaskDAO anomalyTaskDAO;
  private AnomalyResultDAO anomalyResultDAO;
  private AnomalyFunctionFactory anomalyFunctionFactory;
  private TaskContext taskContext;
  private ThirdEyeAnomalyConfiguration thirdEyeAnomalyConfiguration;
  private long workerId;

  volatile boolean shutdown = false;
  private static int MAX_PARALLEL_TASK = 3;

  public TaskDriver(ThirdEyeAnomalyConfiguration thirdEyeAnomalyConfiguration, AnomalyJobDAO anomalyJobDAO,
      AnomalyTaskDAO anomalyTaskDAO, AnomalyResultDAO anomalyResultDAO, AnomalyFunctionFactory anomalyFunctionFactory) {
    this.workerId = thirdEyeAnomalyConfiguration.getId();
    this.anomalyTaskDAO = anomalyTaskDAO;
    this.anomalyResultDAO = anomalyResultDAO;
    this.anomalyFunctionFactory = anomalyFunctionFactory;
    taskExecutorService = Executors.newFixedThreadPool(MAX_PARALLEL_TASK);

    taskContext = new TaskContext();
    taskContext.setAnomalyJobDAO(anomalyJobDAO);
    taskContext.setAnomalyTaskDAO(anomalyTaskDAO);
    taskContext.setResultDAO(anomalyResultDAO);
    taskContext.setAnomalyFunctionFactory(anomalyFunctionFactory);
    taskContext.setThirdEyeAnomalyConfiguration(thirdEyeAnomalyConfiguration);
  }

  public void start() throws Exception {
    List<Callable<Void>> callables = new ArrayList<>();
    for (int i = 0; i < MAX_PARALLEL_TASK; i++) {
      Callable<Void> callable = new Callable<Void>() {
        @Override
        public Void call() throws Exception {
          while (!shutdown) {

            LOG.info(Thread.currentThread().getId() + " : Finding next task to execute for threadId:{}",
                Thread.currentThread().getId());

            try {
              // select a task to execute, and update it to RUNNING
              AnomalyTaskSpec anomalyTaskSpec = selectAndUpdate();
              LOG.info(Thread.currentThread().getId() + " : Executing task: {} {}", anomalyTaskSpec.getId(),
                  anomalyTaskSpec.getTaskInfo());

              // execute the selected task
              TaskType taskType = anomalyTaskSpec.getTaskType();
              TaskRunner taskRunner = TaskRunnerFactory.getTaskRunnerFromTaskType(taskType);
              TaskInfo taskInfo = TaskInfoFactory.getTaskInfoFromTaskType(taskType, anomalyTaskSpec.getTaskInfo());
              LOG.info(Thread.currentThread().getId() + " : Task Info {}", taskInfo);
              List<TaskResult> taskResults = taskRunner.execute(taskInfo, taskContext);
              LOG.info(Thread.currentThread().getId() + " : DONE Executing task: {}", anomalyTaskSpec.getId());

              // update status to COMPLETED
              updateStatusAndTaskEndime(anomalyTaskSpec.getId(), TaskStatus.RUNNING, TaskStatus.COMPLETED);
            } catch (Exception e) {
              LOG.error("Exception in electing and executing task", e);
            }
          }
          return null;
        }
      };
      callables.add(callable);
    }
    for (Callable<Void> callable : callables) {
      taskExecutorService.submit(callable);
    }
    LOG.info(Thread.currentThread().getId() + " : Started task driver");
  }

  public void stop() {
    taskExecutorService.shutdown();
  }

  private AnomalyTaskSpec selectAndUpdate() throws Exception {
    LOG.info(Thread.currentThread().getId() + " : Starting selectAndUpdate {}", Thread.currentThread().getId());
    AnomalyTaskSpec acquiredTask = null;
    LOG.info(Thread.currentThread().getId() + " : Trying to find a task to execute");
    do {

      List<AnomalyTaskSpec> anomalyTasks = new ArrayList<>();
      try {
      anomalyTasks =
          anomalyTaskDAO.findByStatusOrderByCreateTimeAscending(TaskStatus.WAITING);
      } catch (OptimisticLockException | RollbackException | StaleObjectStateException e) {
        LOG.warn("OptimisticLockException while select and update, by workerId {}", workerId);
      }
      if (anomalyTasks.size() > 0)
        LOG.info(Thread.currentThread().getId() + " : Found {} tasks in waiting state", anomalyTasks.size());

      for (AnomalyTaskSpec anomalyTaskSpec : anomalyTasks) {
        LOG.info(Thread.currentThread().getId() + " : Trying to acquire task : {}", anomalyTaskSpec.getId());
        boolean success = anomalyTaskDAO.updateStatusAndWorkerId(workerId, anomalyTaskSpec.getId(),
            TaskStatus.WAITING, TaskStatus.RUNNING);
        LOG.info(Thread.currentThread().getId() + " : Task acquired success: {}", success);
        if (success) {
          acquiredTask = anomalyTaskSpec;
          break;
        }
      }
    } while (acquiredTask == null);
    LOG.info(Thread.currentThread().getId() + " : Acquired task ======" + acquiredTask);

    return acquiredTask;
  }

  private void updateStatusAndTaskEndime(long taskId, TaskStatus oldStatus, TaskStatus newStatus) throws Exception {
    LOG.info(Thread.currentThread().getId() + " : Starting updateStatus {}", Thread.currentThread().getId());

    try {
      anomalyTaskDAO.updateStatusAndTaskEndTime(taskId, oldStatus, newStatus, System.currentTimeMillis());
      LOG.info(Thread.currentThread().getId() + " : updated status {}", newStatus);
    } catch (Exception e) {
      LOG.error("Exception in updating status and task end time", e);
    }
  }

}
