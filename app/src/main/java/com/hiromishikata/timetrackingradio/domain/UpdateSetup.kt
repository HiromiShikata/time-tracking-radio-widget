package com.hiromishikata.timetrackingradio.domain

const val NOTIFICATION_PERMISSION_API_LEVEL = 33
const val INSTALL_PERMISSION_API_LEVEL = 26

sealed interface UpdateSetupStep {
    data object RequestNotificationPermission : UpdateSetupStep

    data object OpenUnknownAppSources : UpdateSetupStep
}

fun updateSetupSteps(notificationsPermitted: Boolean, installAllowed: Boolean, apiLevel: Int): List<UpdateSetupStep> =
    buildList {
        if (apiLevel >= NOTIFICATION_PERMISSION_API_LEVEL && !notificationsPermitted) {
            add(UpdateSetupStep.RequestNotificationPermission)
        }
        if (apiLevel >= INSTALL_PERMISSION_API_LEVEL && !installAllowed) {
            add(UpdateSetupStep.OpenUnknownAppSources)
        }
    }
