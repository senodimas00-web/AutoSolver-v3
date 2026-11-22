package com.solver.auto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SolverState {
    enum class Phase { IDLE, BUSY, ERROR }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _auto = MutableStateFlow(false)
    val auto: StateFlow<Boolean> = _auto.asStateFlow()
    private val _status = MutableStateFlow("Siap.")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _solved = MutableStateFlow(0)
    val solved: StateFlow<Int> = _solved.asStateFlow()
    private val _errors = MutableStateFlow(0)
    val errors: StateFlow<Int> = _errors.asStateFlow()

    fun setPhase(p: Phase) { _phase.value = p }
    fun setAuto(v: Boolean) { _auto.value = v }
    fun setStatus(v: String) { _status.value = v }
    fun incSolved() { _solved.value++ }
    fun incErrors() { _errors.value++ }
}
