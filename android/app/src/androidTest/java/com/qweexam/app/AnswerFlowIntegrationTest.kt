package com.qweexam.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.repository.AnswerRepository
import com.qweexam.app.data.repository.BankRepository
import com.qweexam.app.ui.OverlayTextFormatter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机集成测试（M5）：真实题库导入 → Room 入库 → 题干匹配 → 悬浮窗格式化 全链路。
 * 运行：./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AnswerFlowIntegrationTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun importRealBank_thenMatchAndFormat() = runBlocking {
        // 1) 真实题库 题库1.xlsx（500 题，无表头 4 列）
        // 注：测试 APK 的资源要从 instrumentation context（测试包自身）读取，
        // 不能用被测 App 的 context；资源文件名用 ASCII（中文名打包会损坏编码）
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bytes = testContext.assets.open("bank1.xlsx").readBytes()
        val repo = BankRepository(db)
        val (bankId, count) = repo.importBank("题库1.xlsx", bytes)
        assertEquals(500, count)
        assertEquals(500, db.questionDao().countByBank(bankId))

        // 2) 题干匹配（判断题：答案字母 A → 选项"正确"）
        val answerRepo = AnswerRepository(db)
        val result = answerRepo.match(
            "各岗位出勤前应充分休息，班前8小时及班中严禁饮酒及各种含酒精类的饮料，保证以充沛的精力投入工作。"
        )
        assertEquals("ok", result.status)
        assertEquals("正确", result.display)

        // 3) 悬浮窗格式化
        val (answer, meta) = OverlayTextFormatter.format(result)
        assertEquals("正确", answer)
        assertTrue(meta.contains("题库答案"))

        // 4) 多选：A|B|C|D| 答案入库后显示编号文字（题库1 中 151 号为此多选）
        val multi = answerRepo.match("员工的四懂四会中四懂是（  ）。")
        assertEquals("ok", multi.status)
        assertEquals("1.懂身边火灾危险性 2.懂预防火灾的措施 3.懂扑救火灾的方法 4.懂火场逃生的技巧", multi.display)

        // 5) 未命中
        val miss = answerRepo.match("地球上最高的山峰是什么?")
        assertEquals("miss", miss.status)
    }

    @Test
    fun importLargeBank_over500Questions_matchable() = runBlocking {
        // 题库2.xls 1129 题：验证超过 500 题时全量可匹配（回归 DAO LIMIT 500 bug）
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bytes = testContext.assets.open("bank2.xls").readBytes()
        val repo = BankRepository(db)
        val (bankId, count) = repo.importBank("题库2.xls", bytes)
        assertEquals(1129, count)
        assertEquals(1129, db.questionDao().countByBank(bankId))

        val answerRepo = AnswerRepository(db)
        // 第 601 题（索引 600，超出旧 LIMIT 500 范围）：判断，答案 A=正确
        val result = answerRepo.match(
            "信号楼行车控制室兼消防控制室、因此监控房间的钥匙由信号楼保管、此钥匙只能用于火灾报警的情况下、由当班人员取用确认火情、其他情况下任何人不得取用、特殊情况下由室主任授权方可使用。"
        )
        assertEquals("ok", result.status)
        assertEquals("正确", result.display)
    }
}
