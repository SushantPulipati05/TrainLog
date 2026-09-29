# API additions needed for the Workouts tab

The `api/` folder is nested one level too deep for me to reach directly from
here, so everything else (backend, screens, MainActivity) is already written
and pushed to your machine - just these paste-ins are left. There are now two
batches: the first from when the Workouts tab was first added, and a second
for "My Workouts" (custom templates + save-a-past-workout-as-template).

## 1. In `WorkoutApiModels.kt`

Add these data classes anywhere in the file:

```kotlin
@Serializable
data class WorkoutTemplateSummaryResponse(
    val id: Int,
    val name: String,
    val category: String,
    val estimatedMinutes: Int,
    val exerciseCount: Int,
    val isCustom: Boolean
)

@Serializable
data class WorkoutTemplateDetailResponse(
    val id: Int,
    val name: String,
    val category: String,
    val estimatedMinutes: Int,
    val notes: String?,
    val isCustom: Boolean,
    val exercises: List<ExerciseResponse>
)

@Serializable
data class NewWorkoutTemplateRequest(
    val name: String,
    val notes: String? = null,
    val exerciseIds: List<Int>
)

@Serializable
data class SaveWorkoutAsTemplateRequest(
    val name: String? = null,
    val notes: String? = null
)
```

(If you already pasted in `WorkoutTemplateSummaryResponse` /
`WorkoutTemplateDetailResponse` from before, just replace those two with the
updated versions above — they now carry `isCustom` and, for the detail one,
`notes`.)

## 2. In `WorkoutApi.kt`

Add these methods inside the interface (match your existing annotation
style — this follows the pattern of your other endpoints):

```kotlin
@GET("/workout-templates")
suspend fun getWorkoutTemplates(): List<WorkoutTemplateSummaryResponse>

@GET("/workout-templates/{id}")
suspend fun getWorkoutTemplate(@Path("id") templateId: Int): WorkoutTemplateDetailResponse

@POST("/workout-templates")
suspend fun createWorkoutTemplate(@Body request: NewWorkoutTemplateRequest): WorkoutTemplateDetailResponse

@POST("/workouts/{id}/save-as-template")
suspend fun saveWorkoutAsTemplate(
    @Path("id") workoutId: Int,
    @Body request: SaveWorkoutAsTemplateRequest
): WorkoutTemplateDetailResponse
```

That's it — once both are pasted in and the project rebuilds:

- The Workouts tab splits into "My Workouts" (yours) and "Suggested
  Templates" (the six built-in ones).
- The "+" button on that tab opens a modal to build a template from
  scratch — name, notes, and a searchable multi-select exercise list.
  Category and estimated time are always figured out automatically from
  whichever exercises you pick, never asked for.
- On a past workout's detail screen (tap a card under "Previous Workouts"),
  a new bookmark icon next to "Edit" opens "Save as Template" - it defaults
  to that workout's name/notes and turns its distinct exercises into a new
  template under "My Workouts".

Run `railway up` to redeploy the backend, then rebuild the Android app.
