package com.hiromishikata.timetrackingradio.domain

class NetworkUnavailableException(cause: Throwable) : CommunicationFailedException("request to the endpoint failed", cause)
