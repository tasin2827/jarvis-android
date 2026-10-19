package com.tasin.jarvis.ai

import com.tasin.jarvis.domain.Plan

interface AiProvider {
    suspend fun plan(userText: String, foregroundPackage: String?): Result<Plan>
}
