package com.hiromishikata.timetrackingradio.domain

import java.io.IOException

class TransportPhaseFailure(val phase: FailurePhase, val original: IOException) : IOException(original.message, original)
