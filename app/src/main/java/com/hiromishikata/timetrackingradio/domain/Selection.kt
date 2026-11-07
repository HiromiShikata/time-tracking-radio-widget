package com.hiromishikata.timetrackingradio.domain

import java.time.Instant

data class Selection(val cell: CellKey, val since: Instant)
