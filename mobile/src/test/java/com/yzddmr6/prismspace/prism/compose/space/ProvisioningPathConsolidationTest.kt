package com.yzddmr6.prismspace.prism.compose.space

import com.yzddmr6.prismspace.space.SpaceState
import com.yzddmr6.prismspace.prism.compose.vm.specificRootSetupFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProvisioningPathConsolidationTest {

    @Test fun `existing package is enabled without replacement install or verifier changes`() {
        val command = command(maxUsersOriginal = "4")
        assertTrue(command.contains("trap handle_prism_unexpected_exit EXIT"))
        assertTrue(command.contains("trap 'fail_prism_provisioning 70 interrupted' HUP INT TERM"))
        assertTrue(command.contains("pm install-existing --user \"${'$'}PROFILE_ID\" 'com.example'"))
        assertTrue(command.contains("fail_prism_provisioning 30 install_existing"))
        assertTrue(command.contains("am start-user \"${'$'}PROFILE_ID\" || fail_prism_provisioning 50 start"))
        assertTrue(command.contains("echo \"PRISM_PROVISION_FAILED stage=${'$'}PRISM_FAILURE_STAGE\""))
        assertTrue(command.contains("PRISM_PROVISION_SUCCESS user=${'$'}PROFILE_ID"))
        assertTrue(command.contains("fail_prism_provisioning()"))
        assertTrue(command.contains("finish_prism_transaction\n  echo \"PRISM_PROVISION_FAILED stage=${'$'}PRISM_FAILURE_STAGE\"\n  exit \"${'$'}PRISM_FAILURE_STATUS\""))
        assertFalse(command.contains("pm install -r"))
        assertFalse(command.contains("verifier_verify_adb_installs"))
    }

    @Test fun `failed or interrupted transaction rolls back only its newly created profile`() {
        val command = command()
        assertTrue(command.contains("PROFILE_ID=\"\""))
        assertTrue(command.contains("PRISM_PROVISION_SUCCEEDED=0"))
        assertTrue(command.contains("[ -n \"${'$'}PROFILE_ID\" ]"))
        assertTrue(command.contains("pm remove-user \"${'$'}PROFILE_ID\""))
        assertTrue(command.contains("PRISM_PROVISION_ROLLBACK user=${'$'}PROFILE_ID result=success"))
        assertTrue(command.contains("PRISM_PROVISION_SUCCEEDED=1\nfinish_prism_transaction\necho \"PRISM_PROVISION_SUCCESS"))
        assertTrue(command.indexOf("restore_prism_side_effects\n  if [") < command.indexOf("pm remove-user"))
    }

    @Test fun `max users property restores its exact original state`() {
        assertTrue(command(maxUsersOriginal = "4").contains("setprop $MAX_USERS_PROPERTY '4'"))
        assertTrue(command(maxUsersOriginal = null).contains("setprop $MAX_USERS_PROPERTY ''"))
    }

    @Test fun `shell quoting preserves apostrophes in package names`() {
        assertTrue(command(packageName = "com.example.it's").contains("'com.example.it'\"'\"'s'"))
    }

    @Test fun `root transaction remains detached so rollback survives caller interruption`() {
        val launcher = detachedRootProvisioningLauncher("pm install-existing --user 22 com.example", "/data/user/0/app/cache/out")
        assertTrue(launcher.startsWith("setsid sh -c "))
        assertTrue(launcher.contains("</dev/null & echo ${'$'}!"))
        assertTrue(provisioningTransactionFinished(listOf("PRISM_PROVISION_SUCCESS user=22")))
        assertTrue(provisioningTransactionFinished(listOf("PRISM_PROVISION_FAILED stage=install")))
        assertFalse(provisioningTransactionFinished(listOf("Success: created user id 22")))
    }

    @Test fun `completion requires the exact final transaction sentinel`() {
        assertFalse(provisioningCompleted(listOf("Success: created user id 22"), 22))
        assertFalse(provisioningCompleted(listOf("PRISM_PROVISION_SUCCESS user=23"), 22))
        assertTrue(provisioningCompleted(listOf("PRISM_PROVISION_SUCCESS user=22"), 22))
        assertEquals(
            "PRISM_PROVISION_FAILED stage=owner",
            provisioningFailure(listOf("noise", "PRISM_PROVISION_FAILED stage=owner")),
        )
    }

    @Test fun `legacy root setup maps every engine result without changing analytics phases`() {
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.Success, null),
            RootSetupResultMapping.presentation(CreateSpaceResult.Success(22)),
        )
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.ExistingProfile, null),
            RootSetupResultMapping.presentation(CreateSpaceResult.BlockedByState(SpaceState.Healthy(22))),
        )
        listOf(
            CreateSpaceResult.RootUnavailable,
            CreateSpaceResult.CapReached(4),
            CreateSpaceResult.ManagedProfileLimitReached,
            CreateSpaceResult.StateRefreshFailed,
        ).forEach {
            assertEquals(
                RootSetupPresentation(RootSetupUiOutcome.Error, 1),
                RootSetupResultMapping.presentation(it),
            )
        }
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.Error, 2),
            RootSetupResultMapping.presentation(CreateSpaceResult.Failed("install", analyticsPhase = 2)),
        )
        assertEquals(
            RootSetupPresentation(RootSetupUiOutcome.Error, 2),
            RootSetupResultMapping.presentation(CreateSpaceResult.ConvergenceTimeout(22)),
        )
        assertEquals(
            "已达本设备空间上限（最多 4 个用户），无法再创建",
            specificRootSetupFailure(CreateSpaceResult.CapReached(4)),
        )
        assertEquals(
            "本设备系统仅允许一个双开空间（已达系统工作资料上限），无法再创建",
            specificRootSetupFailure(CreateSpaceResult.ManagedProfileLimitReached),
        )
        assertEquals(
            "无法确认双开空间的最新状态，请稍后重试。未执行任何更改。",
            specificRootSetupFailure(CreateSpaceResult.StateRefreshFailed),
        )
        assertEquals(null, specificRootSetupFailure(CreateSpaceResult.Failed("install", analyticsPhase = 2)))
        assertEquals(
            "空间已创建，但空间内初始化未在预期时间内完成。请前往「设置 → 修复双开空间」自动完成初始化。",
            specificRootSetupFailure(CreateSpaceResult.ConvergenceTimeout(22)),
        )
    }

    @Test fun `all deletion entrances use the same pure route decision`() {
        fun facts(
            current: Int = 0,
            owned: Boolean = true,
            healthy: Boolean = true,
            mode: com.yzddmr6.prismspace.prism.compose.vm.PrismMode = com.yzddmr6.prismspace.prism.compose.vm.PrismMode.Normal,
            rootReady: Boolean = false,
        ) = SpaceDeletionFacts(current, 22, owned, healthy, mode, rootReady)

        assertEquals(SpaceDeletionRoute.Self, deletionRoute(facts(current = 22)))
        assertEquals(SpaceDeletionRoute.Root, deletionRoute(facts(
            mode = com.yzddmr6.prismspace.prism.compose.vm.PrismMode.Root,
            rootReady = true,
        )))
        assertEquals(SpaceDeletionRoute.Bridge, deletionRoute(facts(
            mode = com.yzddmr6.prismspace.prism.compose.vm.PrismMode.Root,
            rootReady = false,
        )))
        assertEquals(SpaceDeletionRoute.Refuse, deletionRoute(facts(owned = false)))
        assertEquals(SpaceDeletionRoute.Refuse, deletionRoute(facts(healthy = false)))
        assertEquals(SpaceDeletionRoute.Bridge, deletionRetryRoute(DeleteSpaceResult.RootUnavailable, facts()))
        assertEquals(SpaceDeletionRoute.Refuse, deletionRetryRoute(DeleteSpaceResult.Failed("ambiguous"), facts()))

        listOf(
            File("src/main/java/com/yzddmr6/prismspace/prism/compose/vm/SettingsViewModel.kt"),
        ).forEach { source ->
            val text = source.readText()
            assertTrue("${source.name} must use the coordinator", text.contains("SpaceDeletionCoordinator.delete("))
            assertFalse("${source.name} must not branch to legacy destruction", text.contains("destroyProfileDirect"))
        }
        // 删除双开空间的唯一归属是设置危险区：空间页不再承载删除入口。
        val spaceScreen = File("src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SpaceScreen.kt").readText()
        assertFalse("Space screen must not offer space deletion (settings danger zone owns it)",
            spaceScreen.contains("deleteSpace"))
        val settingsScreen = File("src/main/java/com/yzddmr6/prismspace/prism/compose/screen/SettingsScreen.kt").readText()
        assertTrue("Settings danger zone must wire deletion through the ViewModel",
            settingsScreen.contains("vm.deleteDualSpace("))
        assertTrue("Settings deletion must pass the real clone count into the warning",
            settingsScreen.contains("cloneCount ="))
        // 删除成功后：待安装标记必须清除（待安装任务指向已删除的空间）；「已双开」只由 profile 侧事实推导，
        // 主空间不再有克隆登记表需要清理。
        val settingsVm = File("src/main/java/com/yzddmr6/prismspace/prism/compose/vm/SettingsViewModel.kt").readText()
        val successBlock = settingsVm.substringAfter("DeleteSpaceResult.Success").substringBefore("setFeedback(fb.message")
        assertFalse(successBlock.contains("UserCloneRegistry"))
        assertTrue(successBlock.contains("ClonePreparationStore.clear"))
    }

    @Test fun `main space keeps no clone registry`() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "java") }
            .filter { it.readText().contains("UserCloneRegistry") }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), offenders)
        assertFalse(File("src/main/java/com/yzddmr6/prismspace/controller/UserCloneRegistry.kt").exists())
    }

    @Test fun `profile wipe has exactly one implementation`() {
        val implementationCount = listOf(File("src/main"), File("../shared/src/main"))
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
            .sumOf { source -> Regex("\\.wipeData\\(0\\)").findAll(source.readText()).count() }
        assertEquals(1, implementationCount)
    }

    private fun command(
        maxUsersOriginal: String? = "4",
        packageName: String = "com.example",
    ) = buildRootProvisioningCommand(
        RootProvisioningCommandInput(
            parentUserId = 0,
            temporaryMaxUsers = 4,
            packageName = packageName,
            adminComponent = "com.example/.Admin",
            maxUsersOriginal = maxUsersOriginal,
        ),
    )
}
