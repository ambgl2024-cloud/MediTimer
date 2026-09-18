package com.example.meditimer.notifications

/**
 * Compatibility placeholder.
 *
 * Older MediTimer versions used a foreground CountdownService for minute-by-minute
 * audio cues. From v0.5.0 onward intermediate countdown sounds were removed and the
 * countdown only schedules its final exact alarm, so this service is intentionally
 * unused. The placeholder is kept to safely overwrite stale copies when the project
 * is updated through GitHub's browser uploader, which does not delete removed files.
 */
@Deprecated("No longer used; countdown completion is handled by AlarmManager")
object CountdownService
