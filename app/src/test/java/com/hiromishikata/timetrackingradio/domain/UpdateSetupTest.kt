package com.hiromishikata.timetrackingradio.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateSetupTest {
    @Test
    fun theSetupStepsFollowThePermissionsTheOsRequires() {
        data class Case(
            val apiLevel: Int,
            val notificationsPermitted: Boolean,
            val installAllowed: Boolean,
            val expected: List<UpdateSetupStep>,
        )
        val cases = listOf(
            Case(33, false, true, listOf(UpdateSetupStep.RequestNotificationPermission)),
            Case(33, true, false, listOf(UpdateSetupStep.OpenUnknownAppSources)),
            Case(33, false, false, listOf(UpdateSetupStep.RequestNotificationPermission, UpdateSetupStep.OpenUnknownAppSources)),
            Case(33, true, true, emptyList()),
            Case(32, false, true, emptyList()),
            Case(32, false, false, listOf(UpdateSetupStep.OpenUnknownAppSources)),
            Case(25, true, false, emptyList()),
            Case(26, true, false, listOf(UpdateSetupStep.OpenUnknownAppSources)),
        )
        cases.forEach { case ->
            assertEquals(
                "apiLevel=${case.apiLevel} permitted=${case.notificationsPermitted} installAllowed=${case.installAllowed}",
                case.expected,
                updateSetupSteps(case.notificationsPermitted, case.installAllowed, case.apiLevel),
            )
        }
    }
}
