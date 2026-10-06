package com.example.workoutlog_androidstudio.api

import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Path
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.PUT
import com.example.workoutlog_androidstudio.AuthInterceptor

interface WorkoutApi {
    @POST("workouts")
    suspend fun startWorkout(@Body request: NewWorkoutRequest): WorkoutResponse

    @GET("exercises")
    suspend fun getExercises(): List<ExerciseResponse>

    @GET("workouts/summary")
    suspend fun getWorkoutSummaries(): List<WorkoutSummaryResponse>

    @POST("workouts/{id}/sets")
    suspend fun logSet(@Path("id") workoutId: Int, @Body request: NewSetRequest): SetEntryResponse

    @POST("workouts/{id}/end")
    suspend fun endWorkout(@Path("id") workoutId: Int, @Body request: EndWorkoutRequest): WorkoutResponse

    @GET("workouts/{id}")
    suspend fun getWorkoutDetail(@Path("id") workoutId: Int): WorkoutDetailResponse

    @DELETE("workouts/{id}")
    suspend fun deleteWorkout(@Path("id") workoutId: Int)

    @POST("exercises")
    suspend fun createExercise(@Body request: NewExerciseRequest): ExerciseResponse

    @PATCH("workouts/{id}")
    suspend fun updateWorkout(@Path("id") workoutId: Int, @Body request: UpdateWorkoutRequest): WorkoutResponse

    @PUT("sets/{id}")
    suspend fun updateSet(@Path("id") setId: Int, @Body request: UpdateSetRequest): SetEntryResponse

    @DELETE("sets/{id}")
    suspend fun deleteSet(@Path("id") setId: Int)

    @DELETE("workouts/{workoutId}/exercises/{exerciseId}")
    suspend fun deleteExerciseFromWorkout(@Path("workoutId") workoutId: Int, @Path("exerciseId") exerciseId: Int)

    @GET("profile")
    suspend fun getProfile(): ProfileResponse

    @PATCH("profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): ProfileResponse

    @GET("workout-templates")
    suspend fun getWorkoutTemplates(): List<WorkoutTemplateSummaryResponse>

    @GET("workout-templates/{id}")
    suspend fun getWorkoutTemplate(@Path("id") templateId: Int): WorkoutTemplateDetailResponse

    @POST("workout-templates")
    suspend fun createWorkoutTemplate(@Body request: NewWorkoutTemplateRequest): WorkoutTemplateDetailResponse

    @DELETE("workout-templates/{id}")
    suspend fun deleteWorkoutTemplate(@Path("id") templateId: Int)

    @POST("workouts/{id}/save-as-template")
    suspend fun saveWorkoutAsTemplate(
        @Path("id") workoutId: Int,
        @Body request: SaveWorkoutAsTemplateRequest
    ): WorkoutTemplateDetailResponse

    @GET("exercises/{id}/history")
    suspend fun getExerciseHistory(@Path("id") exerciseId: Int): ExerciseHistoryResponse

    @DELETE("account")
    suspend fun deleteAccount()

    @POST("workouts/{id}/complete")
    suspend fun completeWorkout(@Path("id") workoutId: Int, @Body request: CompleteWorkoutRequest): WorkoutResponse
}

object NetworkClient {
    private const val BASE_URL = "https://service-production-3d95.up.railway.app/"
    private val json = Json { ignoreUnknownKeys = true }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
        redactHeader("Authorization")
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor())
        .addInterceptor(loggingInterceptor)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    val workoutApi: WorkoutApi by lazy {
        retrofit.create(WorkoutApi::class.java)
    }
}
