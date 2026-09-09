package com.boardgamenation.tracker.domain.usecase

import com.boardgamenation.tracker.data.db.entity.AchievementEntity
import com.boardgamenation.tracker.data.photo.SessionPhotoStore
import com.boardgamenation.tracker.data.repository.AchievementRepository
import com.boardgamenation.tracker.data.repository.SessionRepository
import com.boardgamenation.tracker.domain.model.SessionForm
import javax.inject.Inject
import javax.inject.Singleton

data class SaveSessionResult(val sessionId: Long, val newlyUnlocked: List<AchievementEntity>)

/**
 * Saving a play and re-evaluating achievements are one operation, not two.
 *
 * They live together here rather than in a view model so that every path that writes a
 * session — quick log, the full form, the timer, an import — runs the same evaluation.
 * A caller that forgot to would leave the achievements screen quietly wrong.
 */
@Singleton
class SaveSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val achievementRepository: AchievementRepository
) {
    suspend operator fun invoke(form: SessionForm): SaveSessionResult {
        val id = sessionRepository.save(form)
        val unlocked = achievementRepository.evaluateAfterSession(id)
        return SaveSessionResult(id, unlocked)
    }
}

/**
 * Deleting a play can invalidate an achievement that play earned, so this reconciles
 * rather than merely evaluating: unlocks that are no longer deserved are withdrawn.
 */
@Singleton
class DeleteSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val achievementRepository: AchievementRepository,
    private val photoStore: SessionPhotoStore
) {
    suspend operator fun invoke(sessionId: Long) {
        // Read before the row goes, or the only pointer to the photo goes with it.
        val photo = sessionRepository.getSession(sessionId)?.photoUri
        sessionRepository.delete(sessionId)
        photoStore.delete(photo)
        achievementRepository.reconcile()
    }
}

/** Editing changes the numbers underneath achievements just as deleting does. */
@Singleton
class EditSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val achievementRepository: AchievementRepository,
    private val photoStore: SessionPhotoStore
) {
    suspend operator fun invoke(form: SessionForm): SaveSessionResult {
        val previousPhoto = sessionRepository.getSession(form.id)?.photoUri
        val id = sessionRepository.save(form)
        // Only once the swap is written: until then the old photo is still the play's,
        // and an edit that never gets saved must leave it where it was.
        if (previousPhoto != null && previousPhoto != form.photoUri) photoStore.delete(previousPhoto)
        achievementRepository.reconcile()
        val unlocked = achievementRepository.evaluateAfterSession(id)
        return SaveSessionResult(id, unlocked)
    }
}
