package com.example.travelmate.repository

import com.example.travelmate.data.User
import com.example.travelmate.network.ApiService
import com.example.travelmate.network.TokenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AuthRepository(
    private val userRepository: UserRepository,
    private val tokenManager: TokenManager
) {

    suspend fun login(email: String, password: String, enableBiometrics: Boolean): Result<User> {
        val remoteLogin = ApiService.login(email, password)
        if (remoteLogin.isFailure) return Result.failure(remoteLogin.exceptionOrNull()!!)

        val payload = remoteLogin.getOrNull()
        tokenManager.saveAuth(payload?.token, payload?.role, email)

        val localUser = withContext(Dispatchers.IO) { userRepository.loginUser(email, password) }
        val resolvedUser = localUser ?: withContext(Dispatchers.IO) {
            userRepository.registerUser(email, password, role = payload?.role ?: "user", name = email)
            userRepository.getUserByEmail(email)
        }

        resolvedUser?.let {
            if (!enableBiometrics) {
                withContext(Dispatchers.IO) {
                    userRepository.updateUseBiometrics(email, false)
                }
            }
        }

        return resolvedUser?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Unable to resolve user locally"))
    }

    suspend fun register(name: String, email: String, password: String): Result<User> {
        val remoteResult = ApiService.register(name, email, password)
        if (remoteResult.isFailure) return Result.failure(remoteResult.exceptionOrNull()!!)

        val payload = remoteResult.getOrNull()
        tokenManager.saveAuth(payload?.token, payload?.role, email)

        withContext(Dispatchers.IO) {
            userRepository.registerUser(email, password, role = payload?.role ?: "user", name = name)
        }
        val created = withContext(Dispatchers.IO) { userRepository.getUserByEmail(email) }
        return created?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("Unable to create user locally"))
    }

    fun logout() {
        tokenManager.clearAuth()
    }
}
