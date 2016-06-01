package com.netflix.priam.backup.parallel;

import java.util.AbstractSet;
import java.util.Date;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Singleton;
import com.netflix.priam.backup.AbstractBackupPath;

/*
 * 
 * Represents a queue of files (incrementals, snapshots, meta files) to be uploaded.
 * 
 * Duties of the mgr include:
 * - Mechanism to add a task, including de-duplication of tasks before adding to queue.
 * - Guarantee delivery of task to only one consumer.
 * - Provide relevant metrics including number of tasks in queue, etc.
 */

@Singleton
public class CassandraBackupQueueMgr implements ITaskQueueMgr<AbstractBackupPath> {

	private static final Logger logger = LoggerFactory.getLogger(CassandraBackupQueueMgr.class);
	
	//TODO: FP - if small upper bound, the producer will wait until space becomes avaiable
	BlockingQueue<AbstractBackupPath> tasks = new ArrayBlockingQueue<AbstractBackupPath>(10);
	//Key to task is the S3 absolute path (BASE/REGION/CLUSTER/TOKEN/[yyyymmddhhmm]/[SST|SNP|META]/KEYSPACE/COLUMNFAMILY/FILE
	AbstractSet<String> tasksQueued = new HashSet<String>(); //TODO: initialize to half size of tasks queue
	
	@Override
	/*
	 * Add task to queue if it does not already exist.  For performance reasons, this behavior does not acquire a lock on the queue hence
	 * it is up to the caller to handle possible duplicate tasks.
	 * 
	 * Note: will block until there is space in the queue.
	 */
	public void add(AbstractBackupPath task) {
		if ( !tasksQueued.contains(task.getRemotePath())) {
			tasksQueued.add(task.getRemotePath());
			try {
				tasks.put(task); //block until space becomes available in queue
                logger.info(String.format("Queued file %s within CF %s", task.getFileName(), task.getColumnFamily()));
				
			} catch (InterruptedException e) {
				logger.warn("Interrupted waiting for the task queue to have free space, not fatal will just move on.   Error Msg: " + e.getLocalizedMessage());
			} 
		} else {
			logger.info("Already in queue, no-op.  File: " + task.getRemotePath());
		}
		
		return;
	}

	@Override
	/*
	 * Guarantee delivery of a task to only one consumer.
	 * 
	 * @return task, null if task in queue.
	 */
	public AbstractBackupPath take() throws InterruptedException {
		AbstractBackupPath task = null;
		if ( !tasks.isEmpty() ) {
			
			synchronized(tasks) {
				task =  tasks.poll(); //non-blocking call
			}
		}
		
		return task;
	}

	@Override
	/*
	 * @return true if there are more tasks.  
	 * 
	 * Note: this is a best effort so the caller should call me again just before taking a task.
	 * We anticipate this method will be invoked at a high frequency hence, making it thread-safe will slow down the appliation or
	 * worse yet, create a deadlock.  For example, caller blocks to determine if there are more tasks and also blocks waiting to dequeue
	 * the task.
	 */
	public Boolean hasTasks() {
		return tasks.isEmpty() ? false: true;
	}

	@Override
	/*
	 * A means to perform any post processing once the task has been completed.  If post processing is needed,
	 * the consumer should notify this behavior via callback once the task is completed. 
	 * 
	 * *Note: "completed" here can mean success or failure.
	 */
	public void taskPostProcessing(AbstractBackupPath completedTask) {
		this.tasksQueued.remove(completedTask.getRemotePath());
	}

	@Override
	/*
	 * @return num of pending tasks.  Note, the result is a best guess, don't rely on it to be 100% accurate.
	 */
	public Integer getNumOfTasksToBeProessed() {
		return tasks.size();
	}

	@Override
	public Boolean tasksCompleted(Date date) {
		throw new UnsupportedOperationException();
	}

}