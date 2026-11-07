package com.hiromishikata.timetrackingradio.usecase

import com.hiromishikata.timetrackingradio.domain.CommunicationFailedException
import com.hiromishikata.timetrackingradio.domain.SelectionRepository
import com.hiromishikata.timetrackingradio.domain.SelectionRequest
import com.hiromishikata.timetrackingradio.domain.SelectionResponse

class FakeSelectionRepository(
    private val onRead: () -> SelectionResponse = { throw CommunicationFailedException("read not scripted") },
    private val onUpdate: () -> SelectionResponse = { throw CommunicationFailedException("update not scripted") },
) : SelectionRepository {
    val updates = mutableListOf<SelectionRequest>()

    override fun read(): SelectionResponse = onRead()

    override fun update(request: SelectionRequest): SelectionResponse {
        updates += request
        return onUpdate()
    }
}
