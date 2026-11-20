package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AutomationDao {

    @Transaction
    @Query("SELECT * FROM automation_tasks ORDER BY createdAt DESC")
    fun observeTasksWithSteps(): Flow<List<TaskWithSteps>>

    @Transaction
    @Query("SELECT * FROM automation_tasks WHERE id = :taskId")
    fun observeTaskWithSteps(taskId: Long): Flow<TaskWithSteps?>

    @Transaction
    @Query("SELECT * FROM automation_tasks WHERE id = :taskId")
    suspend fun getTaskWithSteps(taskId: Long): TaskWithSteps?

    @Transaction
    @Query("SELECT * FROM automation_tasks ORDER BY createdAt DESC")
    suspend fun getAllTasksWithSteps(): List<TaskWithSteps>

    @Query("SELECT * FROM automation_tasks ORDER BY createdAt DESC")
    suspend fun getAllTasks(): List<AutomationTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: AutomationTaskEntity): Long

    @Update
    suspend fun updateTask(task: AutomationTaskEntity)

    @Query("DELETE FROM automation_tasks WHERE id = :taskId")
    suspend fun deleteTaskById(taskId: Long)

    @Query("UPDATE automation_tasks SET isEnabled = :enabled WHERE id = :taskId")
    suspend fun setTaskEnabled(taskId: Long, enabled: Boolean)

    @Query(
        "UPDATE automation_tasks SET lastRunTimestamp = :timestamp, lastRunSuccess = :success, " +
            "runCount = runCount + 1, totalClicksExecuted = totalClicksExecuted + :clicks WHERE id = :taskId"
    )
    suspend fun recordTaskRun(taskId: Long, timestamp: Long, success: Boolean, clicks: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<AutomationStepEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: AutomationStepEntity): Long

    @Query("DELETE FROM automation_steps WHERE taskId = :taskId")
    suspend fun deleteStepsForTask(taskId: Long)

    @Query("SELECT COUNT(*) FROM automation_steps WHERE taskId = :taskId")
    suspend fun getStepCountForTask(taskId: Long): Int

    @Transaction
    suspend fun saveTaskWithSteps(
        task: AutomationTaskEntity,
        steps: List<AutomationStepEntity>
    ): Long {
        val savedTaskId = if (task.id == 0L) {
            insertTask(task)
        } else {
            updateTask(task)
            task.id
        }
        deleteStepsForTask(savedTaskId)
        val orderedSteps = steps.mapIndexed { index, step ->
            step.copy(id = 0, taskId = savedTaskId, stepOrder = index)
        }
        if (orderedSteps.isNotEmpty()) {
            insertSteps(orderedSteps)
        }
        return savedTaskId
    }

    @Transaction
    @Query("SELECT * FROM execution_logs ORDER BY startedAt DESC LIMIT 100")
    fun observeExecutionLogsWithEvents(): Flow<List<ExecutionLogWithEvents>>

    @Query("SELECT * FROM execution_click_events ORDER BY timestamp DESC LIMIT 300")
    fun observeRecentClickEvents(): Flow<List<ExecutionClickEventEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExecutionLog(log: ExecutionLogEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClickEvents(events: List<ExecutionClickEventEntity>)

    @Transaction
    suspend fun insertExecutionLogWithEvents(
        log: ExecutionLogEntity,
        events: List<ExecutionClickEventEntity>
    ): Long {
        val logId = insertExecutionLog(log)
        if (events.isNotEmpty()) {
            insertClickEvents(events.map { it.copy(id = 0, logId = logId) })
        }
        return logId
    }

    @Query("DELETE FROM execution_click_events")
    suspend fun clearClickEvents()

    @Query("DELETE FROM execution_logs")
    suspend fun clearExecutionLogs()

    @Transaction
    suspend fun clearAllLogs() {
        clearClickEvents()
        clearExecutionLogs()
    }
}
