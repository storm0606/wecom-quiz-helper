package com.qweexam.app.data.repository

import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.db.BankEntity
import com.qweexam.app.data.db.QuestionEntity
import com.qweexam.app.data.parser.BankParser
import com.qweexam.app.data.parser.Normalizer
import com.qweexam.app.data.parser.Question

/** 题库导入/查询仓库：解析 → 归一化 → 入库 */
class BankRepository(val db: AppDatabase) {

    /** 导入题库：解析文件并写入数据库，返回入库题数 */
    suspend fun importBank(
        fileName: String,
        data: ByteArray,
        mapping: Map<String, Int>? = null,
        onProgress: ((Int) -> Unit)? = null
    ): Pair<Long, Int> {
        val questions = BankParser.loadBank(fileName, data, mapping)
        val bankId = db.bankDao().insert(
            BankEntity(name = fileName.substringBeforeLast('.'), filePath = fileName, questionCount = questions.size)
        )
        val entities = questions.map { it.toEntity(bankId) }
        // 分批插入，避免大题库一次性事务过大
        entities.chunked(500).forEach { chunk ->
            db.questionDao().insertAll(chunk)
            onProgress?.invoke(chunk.size)
        }
        return bankId to entities.size
    }

    /** 解析预览（不入库）：返回题目列表（用于手动列映射后的重新解析） */
    fun parse(fileName: String, data: ByteArray, mapping: Map<String, Int>? = null): List<Question> =
        BankParser.loadBank(fileName, data, mapping)

    /** 删除题库及其题目 */
    suspend fun deleteBank(id: Long) {
        db.questionDao().deleteByBank(id)
        db.bankDao().delete(id)
    }

    /** 重命名题库 */
    suspend fun renameBank(id: Long, name: String) = db.bankDao().rename(id, name)

    private fun Question.toEntity(bankId: Long): QuestionEntity = QuestionEntity(
        bankId = bankId,
        seq = seq,
        qtype = type.label,
        stem = stem,
        stemNorm = Normalizer.normalize(stem),
        optionA = options["A"],
        optionB = options["B"],
        optionC = options["C"],
        optionD = options["D"],
        optionE = options["E"],
        optionF = options["F"],
        answer = answer
    )
}
