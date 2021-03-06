package org.magellan.faleiro;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.ByteString;
import org.apache.mesos.Protos;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.magellan.faleiro.JsonTags.TaskData;
import static org.magellan.faleiro.JsonTags.VerboseStatus;
import static org.magellan.faleiro.JsonTags.SimpleStatus;

public class MagellanJob {
    private static final Logger log = Logger.getLogger(MagellanJob.class.getName());

    // These constants are used to tell the framework how much of each
    // resource each task created by this job needs to execute
    private final double NUM_CPU;
    private final double NUM_MEM;
    private final double NUM_NET_MBPS;
    private final double NUM_DISK;
    private final int NUM_PORTS;

    private final long jobID;

    private final String jobName;

    private final long jobStartingTime;

    private AtomicLong jobFinishingTime = new AtomicLong();

    // How long each task runs for
    private int jobTaskTime;

    // The current best solution as determined by all the running tasks
    private String jobCurrentBestSolution = "";

    // The energy of the current best solution. In our system, a lower energy translates to a better solution
    private double jobBestEnergy = Double.MAX_VALUE;
    private Object jobBestEnergy_lock = new Object();

    // This comes from the client and tells the agent the name of the executor to run for tasks created by this job
    private String jobTaskName;

    // Additional parameters passed in from the user
    private final JSONObject jobAdditionalParam;

    // A list of the best energies found by every task run by this job.
    //private ConcurrentLinkedDeque<Double> energyHistory = new ConcurrentLinkedDeque<>();
    private JSONArray energyHistory = new JSONArray();

    private Object energyHistory_lock = new Object();

    // This list stores tasks that are ready to be scheduled. This list is then consumed by the
    // MagellanFramework when it is ready to accept new tasks.
    private BlockingQueue<MagellanTaskRequest> pendingTasks = new LinkedBlockingQueue<>();

    private JobState state = JobState.INITIALIZED;

    private Protos.ExecutorInfo taskExecutor;

    /* lock to wait for division task to complete */
    private Object division_lock = new Object();

    private Object returnedResult_lock = new Object();
    private AtomicInteger retLength = new AtomicInteger(); // used to prevent constant atomic access of returnedResult

    private AtomicBoolean division_is_done = new AtomicBoolean(false);
    private AtomicBoolean ran_before = new AtomicBoolean(false);

    /* task ID of division, waiting until this is returned to make more tasks */
    private String divisionTaskId;

    /* json array of returned division. iterated through to make new tasks */
    private JSONArray returnedResult;

    private BitSet finishedTasks; // every access sync on finishedTasks_lock object
    private Object finishedTasks_lock = new Object();

    private int currentTask; //index of curent task, used to prevent getting lock on every access
    private Object curTaskObj; //current task object, used to prevent getting lock on every access

    /**
     *
     * @param id Unique Job id
     * @param jName Name of job
     * @param taskName Name of the task we want to execute on the executor side
     * @param jso Additional Job param
     */
    public MagellanJob(long id,
                       String jName,
                       int taskTime,
                       String taskName,
                       JSONObject jso)
    {
        jobID = id;
        jobName = jName;
        jobTaskTime = taskTime;
        jobTaskName = taskName;
        jobAdditionalParam = jso;
        taskExecutor = registerExecutor(System.getenv("EXECUTOR_PATH"));
        jobStartingTime = System.currentTimeMillis();

        NUM_CPU = 1;
        NUM_MEM = 32;
        NUM_NET_MBPS = 0;
        NUM_DISK = 0;
        NUM_PORTS = 0;

        log.log(Level.CONFIG, "New Job created. ID is " + jobID);
    }

    /**
     *
     * @param j : JSONObject from zookeeper used for creating a new job on this framework based on
     *            the state of a job from another, deceased framework
     */
    public MagellanJob(JSONObject j){

        //Reload constants
        NUM_CPU = j.getDouble(VerboseStatus.NUM_CPU);
        NUM_MEM = j.getDouble(VerboseStatus.NUM_MEM);
        NUM_NET_MBPS = j.getDouble(VerboseStatus.NUM_NET_MBPS);
        NUM_DISK = j.getDouble(VerboseStatus.NUM_DISK);
        NUM_PORTS = j.getInt(VerboseStatus.NUM_PORTS);
        log.log(Level.INFO, "constructed with zookeeper object, initializing saved state..");
        if(j.getBoolean(VerboseStatus.RAN_BEFORE) == true){
            String stringEncoding = j.getString(VerboseStatus.BITFIELD_FINISHED);
            finishedTasks = BitSet.valueOf(Base64.getDecoder().decode(stringEncoding)); //in constructor, thread safe
            ran_before.set(j.getBoolean(VerboseStatus.RAN_BEFORE));
            log.log(Level.INFO,"division is done. loading: ");
            log.log(Level.INFO, "ran_before = " + ran_before.get());
            log.log(Level.INFO,"\tfinishedTasks = " + finishedTasks);
            log.log(Level.INFO,"\tfinishedTasks as base64 = " + stringEncoding);
        }

        jobID = j.getInt(SimpleStatus.JOB_ID);
        jobStartingTime = j.getLong(SimpleStatus.JOB_STARTING_TIME);
        jobName = j.getString(SimpleStatus.JOB_NAME);
        jobTaskTime = j.getInt(SimpleStatus.TASK_SECONDS);
        jobTaskName = j.getString(SimpleStatus.TASK_NAME);
        jobCurrentBestSolution = j.getString(SimpleStatus.BEST_LOCATION);
        jobBestEnergy = j.getDouble(SimpleStatus.BEST_ENERGY);
        synchronized (energyHistory_lock) {
            energyHistory = j.getJSONArray(SimpleStatus.ENERGY_HISTORY);
        }
        jobAdditionalParam = j.getJSONObject(SimpleStatus.ADDITIONAL_PARAMS);
        state = (new Gson()).fromJson(j.getString(SimpleStatus.CURRENT_STATE), JobState.class);


        taskExecutor = registerExecutor("/usr/local/bin/enrique");

        log.log(Level.CONFIG, "Reviving job from zookeeper. State is " + state + " . Id is " + jobID);
    }

    /**
     *  Creates an executor object. This object will eventually be run on an agent when it get schedules
     * @param pathToExecutor
     * @return
     */
    public Protos.ExecutorInfo registerExecutor(String pathToExecutor){
        return  Protos.ExecutorInfo.newBuilder()
                .setExecutorId(Protos.ExecutorID.newBuilder().setValue("default"))
                .setCommand(Protos.CommandInfo.newBuilder().setValue(pathToExecutor))
                .setName("SA Job Executor")
                .setSource("java_test")
                .build();
    }

    /**
     * Runs the main loop in a separate thread
     */
    public void start() {
        state = JobState.RUNNING;

        new Thread(() -> {
            run();
        }).start();
    }

    /**
     * This functions creates tasks that are passed to the magellan framework using an annealing approach.
     * We determine the starting location of each task using a temperature cooling mechanism where early on
     * in the execution of this job, more risks are taken and more tasks run in random locations in an attempt
     * to explore more of the search space. As time increases and the temperature of the job decreases, tasks
     * are given starting locations much closer to the global, best solution for this job so that the neighbors
     * of the best solution are evaluated thoroughly in the hopes that they lie close to the global maximum.
     */
    private void run() {
        /* first create the splitter task */
        if(state == JobState.STOP) {
            return;
        }

        while(state==JobState.PAUSED){
            Thread.yield();
            // wait while job is paused
        }

        try {
            // To keep the task ids unique throughout the global job space, use the job ID to
            // ensure uniqueness
            String newTaskId = "" + jobID + "_" + "div";

            // Choose the magellan specific parameters for the new task
            //ByteString data = pickNewTaskStartingLocation(jobTaskTime, jobTaskName, newTaskId, jobAdditionalParam);

            int divisions = 0;

            // Add the task to the pending queue until the framework requests it
            MagellanTaskRequest newTask = new MagellanTaskRequest(
                    newTaskId,
                    jobName,
                    NUM_CPU,
                    NUM_MEM,
                    NUM_NET_MBPS,
                    NUM_DISK,
                    NUM_PORTS,
                    packTaskData(
                            newTaskId,
                            jobTaskName,
                            TaskData.RESPONSE_DIVISIONS,
                            jobAdditionalParam,
                            divisions
                    )
            );

            pendingTasks.put(newTask);
            divisionTaskId = newTaskId;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        /* lock until notified that division has returned */
        log.log(Level.INFO, "waiting for to enter division_lock sync block");
        synchronized (division_lock) {
            try {
                log.log(Level.INFO, "waiting for division_is_done");
                while (division_is_done.get() == false) {
                    division_lock.wait();
                }
            } catch (InterruptedException e) {
                log.log(Level.SEVERE, e.getMessage());
            }
        }

        if(ran_before.get() == false) {
            /* got result of division task */
            synchronized (returnedResult_lock) {
                finishedTasks = new BitSet(retLength.get()); // initialize list of isFinished bits for each task. Persisted across crash.
            }
            ran_before.set(true);
        }

        synchronized (returnedResult_lock) {
            retLength.set(returnedResult.length());
        }

        for (currentTask = 0; currentTask < retLength.get(); currentTask++) {

            synchronized (returnedResult_lock){
                curTaskObj = returnedResult.get(currentTask);
            }

            while(state==JobState.PAUSED){
                Thread.yield();
                // wait while job is paused
            }

            if(state == JobState.STOP) {
                return;
            }

            // check if this index was already completed in a previous run, if so skip it
            boolean tmpCurrentTask;
            synchronized (finishedTasks_lock){
                tmpCurrentTask = finishedTasks.get(currentTask);
            }
            if(!tmpCurrentTask){
                 /* got a list of all the partitions, create a task for each */
                try {
                    String newTaskId = "" + jobID + "_" + currentTask;


                    MagellanTaskRequest newTask = new MagellanTaskRequest(
                            newTaskId,
                            jobName,
                            NUM_CPU,
                            NUM_MEM,
                            NUM_NET_MBPS,
                            NUM_DISK,
                            NUM_PORTS,
                            packTaskData(
                                    newTaskId,
                                    jobTaskName,
                                    TaskData.TASK_ANNEAL,
                                    jobTaskTime/(60.0 * retLength.get()),
                                    jobAdditionalParam,
                                    curTaskObj)
                    );

                    // Add the task to the pending queue until the framework requests it
                    pendingTasks.put(newTask);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        log.log(Level.INFO, "Finished sending tasks. Waiting now. Tasks sent = " + retLength.get());

        int tmpCardinality;

        synchronized (finishedTasks_lock){
            tmpCardinality = finishedTasks.cardinality();
        }

        while(state != JobState.STOP && (retLength.get() != tmpCardinality)) {
            // wait for all tasks to finish
            synchronized (finishedTasks_lock){
                tmpCardinality = finishedTasks.cardinality();
            }
            Thread.yield();
        }

        if(state!=JobState.STOP) {
            state = JobState.DONE;
        }

        synchronized (jobBestEnergy_lock) {
            log.log(Level.INFO, "[Job " + jobID + "]" + " done. Best fitness (" + jobBestEnergy + ") achieved at location " + jobCurrentBestSolution);
        }
    }

    /**
     * Called by the magellan framework to get a list of tasks that this job wants scheduled.
     * @return
     */
    public ArrayList<MagellanTaskRequest> getPendingTasks(){
        ArrayList<MagellanTaskRequest> pt = new ArrayList<>();
        pendingTasks.drainTo(pt);
        return pt;
    }

    /**
     * Job is done if it terminates naturally or if it receives an explicit signal to stop
     * @return
     */
    public boolean isDone(){
        return state == JobState.DONE || state == JobState.STOP;
    }

    /**
     * Called by magellan framework when a message from the executor is sent to this job. This message
     * could indicate that the task was successful, or failed.
     * @param taskState : Indicates the status of the task. Could be TASK_FINISHED, TASK_ERROR, TASK_FAILED,
     *                  TASK_LOST
     * @param taskId : Id of the task
     * @param data   : Data of the task
     */
    public void processIncomingMessages(Protos.TaskState taskState, String taskId, String data) {
        log.log(Level.INFO, "processIncomingMessages: state: " + state + " , taskId: " + taskId);
        log.log(Level.FINER, "data: " + data);
        boolean isDiv = true;
        String[] parts = taskId.split("_");
        String strReturnedJobId = parts[0];
        long returnedJobId = Integer.parseInt(strReturnedJobId);
        int returnedTaskNum = 0;
        String strReturnedTaskNum = parts[1];

        // check that task result is for me, should always be true
        if(returnedJobId != this.jobID){
            log.log(Level.SEVERE, "Job: " + getJobID() + " got task result meant for Job: " + returnedJobId);
            System.exit(-1);
        }

        if(!parts[1].equals("div")) {
            isDiv = false;
            returnedTaskNum = Integer.parseInt(strReturnedTaskNum);
        }

        if(taskState == Protos.TaskState.TASK_ERROR || taskState == Protos.TaskState.TASK_FAILED || taskState == Protos.TaskState.TASK_LOST){
            if(state != JobState.STOP){
                log.log(Level.WARNING, "Problem with task, rescheduling it");

                try {
                    String newTaskId = taskId;
                    MagellanTaskRequest newTask;
                    if(isDiv){
                        int divisions = 0;

                        // Add the task to the pending queue until the framework requests it
                        newTask = new MagellanTaskRequest(
                                newTaskId,
                                jobName,
                                NUM_CPU,
                                NUM_MEM,
                                NUM_NET_MBPS,
                                NUM_DISK,
                                NUM_PORTS,
                                packTaskData(
                                        newTaskId,
                                        jobTaskName,
                                        TaskData.RESPONSE_DIVISIONS,
                                        jobAdditionalParam,
                                        divisions
                                )
                        );
                    }else {
                        newTask = new MagellanTaskRequest(
                                newTaskId,
                                jobName,
                                NUM_CPU,
                                NUM_MEM,
                                NUM_NET_MBPS,
                                NUM_DISK,
                                NUM_PORTS,
                                packTaskData(
                                        newTaskId,
                                        jobTaskName,
                                        TaskData.TASK_ANNEAL,
                                        jobTaskTime / (60.0 * retLength.get()),
                                        jobAdditionalParam,
                                        returnedResult.get(returnedTaskNum)
                                )
                        );
                    }
                    while(state==JobState.PAUSED){
                        Thread.yield();
                        // wait while job is paused
                    }
                    // Add the task to the pending queue until the framework requests it
                    pendingTasks.put(newTask);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return;
        }

        if(data == null){
            return;
        }

        // Retrieve the data sent by the executor
        JSONObject js = new JSONObject(data);

        String returnedTaskId = js.getString(TaskData.UID);

        log.log(Level.INFO, "returnedTaskId is " + returnedTaskId);
        log.log(Level.INFO, "divisionTaskId is " + divisionTaskId);

        if(returnedTaskId.equals(divisionTaskId)) {
            log.log(Level.INFO, "equal, now waiting for division_lock");
            synchronized (division_lock) {
                log.log(Level.INFO, "equal, got division_lock");
                /* parse out the result to get list of tasks */
                synchronized (returnedResult_lock) {
                    returnedResult = js.getJSONArray(TaskData.RESPONSE_DIVISIONS);
                }
                division_is_done.set(true);
                log.log(Level.INFO, "notifying division_lock");
                division_lock.notify();
            }
            return;
        }
        /* not an error and not a division, get results */
        double fitness_score = js.getDouble(TaskData.FITNESS_SCORE);
        String best_location = js.getString(TaskData.BEST_LOCATION);

        synchronized (returnedResult_lock) {
            finishedTasks.set(returnedTaskNum); // mark task as finished. needed for zookeeper state revival
        }

        synchronized (energyHistory_lock) {
            energyHistory.put(fitness_score);
        }
        // If a better score was discovered, make this our global, best location

        synchronized (jobBestEnergy_lock) {
            if (fitness_score < jobBestEnergy) {
                jobCurrentBestSolution = best_location;
                jobBestEnergy = fitness_score;
            }
        }
        log.log(Level.FINE, "Job: " + getJobID() + " processed finished task");
        jobFinishingTime.set(System.currentTimeMillis());
    }

    public void stop() {
        log.log(Level.INFO, "Job: " + getJobID() + " asked to stop");
        state = JobState.STOP;
    }

    public void pause() {
        if(!isDone()) {
            log.log(Level.INFO, "Job: " + getJobID() + " asked to pause");
            state = JobState.PAUSED;
        }
    }

    public void resume(){
        if(!isDone()) {
            log.log(Level.INFO, "Job: " + getJobID() + " asked to resume");
            state = JobState.RUNNING;
        }
    }

    /**
     * Called by the zookeeper service to transfer a snapshot of the current state of the job to save in
     * case this node goes down. This contains information from getSimpleStatus() as well as
     * additional, internal information
     *
     * @return A snapshot of all the important information in this job
     */
    public JSONObject getStateSnapshot() {
        JSONObject jsonObj = getSimpleStatus();

        // Store constants
        jsonObj.put(VerboseStatus.NUM_CPU, NUM_CPU); // final, thread safe
        jsonObj.put(VerboseStatus.NUM_MEM, NUM_MEM); // final, thread safe
        jsonObj.put(VerboseStatus.NUM_NET_MBPS, NUM_NET_MBPS); // final, thread safe
        jsonObj.put(VerboseStatus.NUM_DISK, NUM_DISK); // final, thread safe
        jsonObj.put(VerboseStatus.NUM_PORTS, NUM_PORTS); // final, thread safe

        if(ran_before.get() == false){ //atomic object
            // if null wipe entry
            log.log(Level.FINE,"have not ran before. saving: ");
            log.log(Level.FINE,"\tran_before = " + false);
            log.log(Level.FINE,"\treturnedResult = null");
            log.log(Level.FINE,"\tfinishedTasks = null");
            jsonObj.put(VerboseStatus.RAN_BEFORE, ran_before.get());
        }else {
            /* save all three states after division is complete */
            log.log(Level.FINE,"ran before. saving: ");
            log.log(Level.FINE,"\tran_before = " + ran_before.get()); //atomic object
            log.log(Level.FINE,"\treturnedResult.lenth = " + retLength.get()); //atomic object
            synchronized (finishedTasks_lock) {
                log.log(Level.FINE, "\tfinishedTasks = " + finishedTasks);
                log.log(Level.FINE, "\tfinishedTasks as base64 = " + (Base64.getEncoder().encodeToString(finishedTasks.toByteArray())));
                jsonObj.put(VerboseStatus.BITFIELD_FINISHED, (Base64.getEncoder().encodeToString(finishedTasks.toByteArray())));
            }
            jsonObj.put(TaskData.RESPONSE_DIVISIONS, returnedResult);
            jsonObj.put(VerboseStatus.RAN_BEFORE, ran_before.get());
        }

        return  jsonObj;
    }

    /**
     * This method returns information that the client wants to know about the job
     * @return
     */
    public JSONObject getSimpleStatus() {
        JSONObject jsonObj = new JSONObject();
        jsonObj.put(SimpleStatus.JOB_ID, getJobID());
        jsonObj.put(SimpleStatus.JOB_NAME, getJobName());
        jsonObj.put(SimpleStatus.JOB_STARTING_TIME, getStartingTime());
        jsonObj.put(SimpleStatus.JOB_FINISHING_TIME, getFinishTime());
        jsonObj.put(SimpleStatus.TASK_SECONDS, getTaskTime());
        jsonObj.put(SimpleStatus.TASK_NAME, getJobTaskName());
        jsonObj.put(SimpleStatus.BEST_LOCATION, getBestLocation());
        jsonObj.put(SimpleStatus.BEST_ENERGY, getBestEnergy());
        synchronized (energyHistory_lock) {
            jsonObj.put(SimpleStatus.ENERGY_HISTORY, getEnergyHistory());
        }
        synchronized (finishedTasks_lock) {
            jsonObj.put(SimpleStatus.NUM_FINISHED_TASKS, getNumFinishedTasks());
        }
        jsonObj.put(SimpleStatus.NUM_TOTAL_TASKS, getNumTotalTasks());
        jsonObj.put(SimpleStatus.ADDITIONAL_PARAMS, getJobAdditionalParam());
        jsonObj.put(SimpleStatus.CURRENT_STATE, getState());
        return jsonObj;
    }

    /**
     * Takes the given parameters and packages it into a json formatted Bytestring which can be
     * packaged into a TaskInfo object by the magellan framework
     * @param newTaskId
     * @param jobTaskName
     * @param command
     * @param jobAdditionalParam
     * @param taskData
     * @return
     */
    private ByteString packTaskData(
            String newTaskId,
            String jobTaskName,
            String command,
            double taskTime,
            JSONObject jobAdditionalParam,
            Object taskData
    ){
        JSONObject jsonTaskData = new JSONObject();
        jsonTaskData.put(TaskData.MINUTES_PER_DIVISION, taskTime);
        jsonTaskData.put(TaskData.TASK_DATA, taskData);
        return packTaskData(
                jsonTaskData,
                newTaskId,
                jobTaskName,
                command,
                jobAdditionalParam
        );
    }

    private ByteString packTaskData(
            String newTaskId,
            String jobTaskName,
            String command,
            JSONObject jobAdditionalParam,
            int divisions
    ){
        JSONObject jsonTaskData = new JSONObject();
        jsonTaskData.put(TaskData.TASK_DIVISIONS, divisions);
        return packTaskData(
                jsonTaskData,
                newTaskId,
                jobTaskName,
                command,
                jobAdditionalParam
        );
    }

    private ByteString packTaskData(
            JSONObject jsonTaskData,
            String newTaskId,
            String jobTaskName,
            String command,
            JSONObject jobAdditionalParam
    ){
        jsonTaskData.put(TaskData.UID, newTaskId);
        jsonTaskData.put(TaskData.TASK_NAME, jobTaskName);
        jsonTaskData.put(TaskData.TASK_COMMAND, command);
        jsonTaskData.put(TaskData.JOB_DATA, jobAdditionalParam);
        return ByteString.copyFromUtf8(jsonTaskData.toString());
    }

    enum JobState{
        INITIALIZED, RUNNING, PAUSED, STOP, DONE;
    }


    /* List of getter methods that will be called to store the state of this job*/
    public JobState getState(){return state;}

    public long getJobID() {return jobID;}

    public String getJobName() {return jobName;}

    public double getTaskTime(){ return jobTaskTime; }

    public String getJobTaskName() {return jobTaskName;}

    public JSONObject getJobAdditionalParam(){ return jobAdditionalParam; }

    public String getBestLocation() { return jobCurrentBestSolution; }

    public double getBestEnergy() {
        double tmpJobBestEnergy;
        synchronized (jobBestEnergy_lock){
            tmpJobBestEnergy = jobBestEnergy;
        }
        return tmpJobBestEnergy;
    }

    public Long getStartingTime() { return jobStartingTime; }

    public Long getFinishTime() { return jobFinishingTime.get(); }

    public JSONArray getEnergyHistory() {
        JSONArray tmpEnergyHistory;
        synchronized (energyHistory_lock){
            tmpEnergyHistory = energyHistory;
        }
        return tmpEnergyHistory;
    }

    public Protos.ExecutorInfo getTaskExecutor() { return taskExecutor; }

    public int getNumFinishedTasks(){
        if(ran_before.get()){
            return finishedTasks.cardinality();
        }else{
            return 0;
        }
    }

    public int getNumTasksSent(){ return currentTask;}

    public int getNumTotalTasks() {
        if(ran_before.get()){
            return retLength.get();
        }else{
            return -1; // number of total tasks is unknown, job has not been divided into tasks yet
        }
    }
}
