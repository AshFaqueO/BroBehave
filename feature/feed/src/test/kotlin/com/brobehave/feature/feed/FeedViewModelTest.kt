package com.brobehave.feature.feed

import com.brobehave.core.data.DataError
import com.brobehave.core.data.DataException
import com.brobehave.core.data.PostCursor
import com.brobehave.core.data.SocialRepository
import com.brobehave.core.model.EventType
import com.brobehave.core.model.FeedPost
import com.brobehave.core.model.ReactionType
import com.brobehave.core.testing.MainDispatcherExtension
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class FeedViewModelTest {
    @JvmField
    @RegisterExtension
    val mainDispatcher = MainDispatcherExtension()

    private val social = mockk<SocialRepository>()

    private fun post(id: String, createdAt: String = "2026-11-12T10:00:00+00:00") = FeedPost(
        id = id,
        userId = "friend",
        type = EventType.MILESTONE,
        localDate = "2026-11-12",
        createdAt = createdAt,
    )

    private fun viewModel(firstPage: List<FeedPost>): FeedViewModel {
        coEvery { social.friendsRanks() } returns Result.success(emptyList())
        coEvery { social.feed(null) } returns Result.success(firstPage)
        return FeedViewModel(social)
    }

    @Test
    fun `first load shows posts and an empty feed is reported as empty`() = runTest {
        assertEquals(listOf("a"), viewModel(listOf(post("a"))).state.value.posts.map { it.id })
        assertTrue(viewModel(emptyList()).state.value.isEmpty)
    }

    @Test
    fun `a reaction shows at once and stays when the server accepts it`() = runTest {
        val original = post("a")
        val vm = viewModel(listOf(original))
        coEvery { social.setReaction(original, ReactionType.FIRE) } returns Result.success(Unit)

        vm.react(original, ReactionType.FIRE)

        val shown = vm.state.value.posts.single()
        assertEquals(ReactionType.FIRE, shown.myReaction)
        assertEquals(1, shown.reactionTotal)
    }

    @Test
    fun `a reaction rolls back and reports the error when the server refuses`() = runTest {
        val original = post("a")
        val vm = viewModel(listOf(original))
        coEvery { social.setReaction(original, ReactionType.HAHA) } returns
            Result.failure(DataException(DataError.NOT_ALLOWED))

        vm.react(original, ReactionType.HAHA)

        val shown = vm.state.value.posts.single()
        assertNull(shown.myReaction)
        assertEquals(0, shown.reactionTotal)
        assertEquals(DataError.NOT_ALLOWED, vm.state.value.error)
    }

    @Test
    fun `load more appends the next page using the last post as cursor and stops at the end`() = runTest {
        val vm = viewModel(listOf(post("a"), post("b", createdAt = "2026-11-11T10:00:00+00:00")))
        val cursor = PostCursor("2026-11-11T10:00:00+00:00", "b")
        coEvery { social.feed(cursor) } returns Result.success(listOf(post("c", "2026-11-10T10:00:00+00:00")))
        coEvery { social.feed(PostCursor("2026-11-10T10:00:00+00:00", "c")) } returns Result.success(emptyList())

        vm.loadMore()
        assertEquals(listOf("a", "b", "c"), vm.state.value.posts.map { it.id })
        assertFalse(vm.state.value.endReached)

        vm.loadMore()
        assertTrue(vm.state.value.endReached)

        vm.loadMore()
        coVerify(exactly = 1) { social.feed(PostCursor("2026-11-10T10:00:00+00:00", "c")) }
    }
}
