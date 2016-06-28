package org.jetbrains.teamcity.github

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.WebLinks
import jetbrains.buildServer.serverSide.oauth.github.GitHubClientEx
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.util.EventDispatcher
import jetbrains.buildServer.vcs.RepositoryState
import jetbrains.buildServer.vcs.RepositoryStateListener
import jetbrains.buildServer.vcs.RepositoryStateListenerAdapter
import jetbrains.buildServer.vcs.VcsRoot
import org.eclipse.egit.github.core.client.RequestException
import org.jetbrains.teamcity.github.action.*
import java.io.IOException
import java.util.*

class WebHooksManager(links: WebLinks,
                      private val repoStateEventDispatcher: EventDispatcher<RepositoryStateListener>,
                      private val myAuthDataStorage: AuthDataStorage,
                      storage: WebHooksStorage) : ActionContext(storage, myAuthDataStorage, links) {

    private val myRepoStateListener: RepositoryStateListenerAdapter = object : RepositoryStateListenerAdapter() {
        override fun repositoryStateChanged(root: VcsRoot, oldState: RepositoryState, newState: RepositoryState) {
            if (!Util.isSuitableVcsRoot(root)) return
            val info = Util.getGitHubInfo(root) ?: return
            val hook = getHook(info) ?: return
            if (!isBranchesInfoUpToDate(hook, newState.branchRevisions, info)) {
                // Mark hook as outdated, probably incorrectly configured
                storage.update(info) {
                    it.correct = false
                }
            }
        }
    }

    fun init(): Unit {
        repoStateEventDispatcher.addListener(myRepoStateListener)
    }

    fun destroy(): Unit {
        repoStateEventDispatcher.removeListener(myRepoStateListener)
    }

    companion object {
        private val LOG = Logger.getInstance(WebHooksManager::class.java.name)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doRegisterWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser): HookAddOperationResult {
        return CreateWebHookAction.doRun(info, client, user, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doGetAllWebHooks(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser): HooksGetOperationResult {
        return GetAllWebHooksAction.doRun(info, client, user, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doUnRegisterWebHook(info: GitHubRepositoryInfo, client: GitHubClientEx, user: SUser): HookDeleteOperationResult {
        return DeleteWebHookAction.doRun(info, client, user, this)
    }

    @Throws(IOException::class, RequestException::class, GitHubAccessException::class)
    fun doTestWebHook(info: GitHubRepositoryInfo, ghc: GitHubClientEx, user: SUser): HookTestOperationResult {
        return TestWebHookAction.doRun(info, ghc, user, this)
    }

    fun updateLastUsed(info: GitHubRepositoryInfo, date: Date) {
        // We should not show vcs root instances in health report if hook was used in last 7 (? or any other number) days. Even if we have not created that hook.
        val hook = getHook(info) ?: return
        val used = hook.lastUsed
        if (used == null || used.before(date)) {
            storage.update(info) {
                @Suppress("NAME_SHADOWING")
                val used = it.lastUsed
                if (used == null || used.before(date)) {
                    it.correct = true
                    it.lastUsed = date
                }
            }
        }
    }

    fun updateBranchRevisions(info: GitHubRepositoryInfo, map: Map<String, String>) {
        val hook = getHook(info) ?: return
        storage.update(info) {
            it.correct = true
            val lbr = hook.lastBranchRevisions ?: HashMap()
            lbr.putAll(map)
            it.lastBranchRevisions = lbr
        }
    }

    private fun isBranchesInfoUpToDate(hook: WebHooksStorage.HookInfo, newBranches: Map<String, String>, info: GitHubRepositoryInfo): Boolean {
        val hookBranches = hook.lastBranchRevisions

        // Maybe we have forgot about revisions (cache cleanup after server restart)
        if (hookBranches == null) {
            storage.update(info) {
                it.lastBranchRevisions = HashMap(newBranches)
            }
            return true
        }
        for ((name, hash) in newBranches.entries) {
            val old = hookBranches[name]
            if (old == null) {
                LOG.warn("Hook $hook have no revision saved for branch $name, but it should be $hash")
                return false
            }
            if (old != hash) {
                LOG.warn("Hook $hook have incorrect revision saved for branch $name, expected $hash but found $old")
                return false
            }
        }
        return true
    }

    fun isHasIncorrectHooks() = storage.isHasIncorrectHooks()
    fun getIncorrectHooks() = storage.getIncorrectHooks()

}
