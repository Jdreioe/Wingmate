package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryCommunicationSessionDataSource : CommunicationSessionDataSource {
    private val mutex = Mutex()
    private var snapshot = CommunicationSessionSnapshot()

    override suspend fun load(): CommunicationStorageResult<CommunicationSessionSnapshot> = mutex.withLock {
        CommunicationStorageResult.Success(snapshot)
    }

    override suspend fun save(
        snapshot: CommunicationSessionSnapshot,
    ): CommunicationStorageResult<Unit> = mutex.withLock {
        this.snapshot = snapshot
        CommunicationStorageResult.Success(Unit)
    }
}
