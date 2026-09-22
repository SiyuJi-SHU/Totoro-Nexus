package org.example.platform;

/** The task owns answer constraints; neither the executor nor a model-selected field can change them. */
enum AnswerPolicy {
    CONVERSATIONAL, GROUNDED;

    static AnswerPolicy forTask(TaskRouter.Task task) {
        return task == TaskRouter.Task.INCIDENT_DIAGNOSIS ? GROUNDED : CONVERSATIONAL;
    }
}
