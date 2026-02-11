package com.tool.smspro.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tool.smspro.data.dao.*
import com.tool.smspro.data.entity.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Customer::class, CustomerGroup::class, SmsTemplate::class, SendTask::class, SendRecord::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun customerDao(): CustomerDao
    abstract fun customerGroupDao(): CustomerGroupDao
    abstract fun smsTemplateDao(): SmsTemplateDao
    abstract fun sendTaskDao(): SendTaskDao
    abstract fun sendRecordDao(): SendRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_pro.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    prepopulateTemplates(database.smsTemplateDao())
                                }
                            }
                        }
                    })
                    .build().also { INSTANCE = it }
            }
        }

        private suspend fun prepopulateTemplates(dao: SmsTemplateDao) {
            if (dao.count() > 0) return
            val templates = listOf(
                SmsTemplate(title = "新品通知", content = "尊敬的{姓名}，我们推出了全新产品系列，诚邀您了解详情。如有兴趣请回复1，我们将安排专人为您介绍。", category = "营销"),
                SmsTemplate(title = "活动邀请", content = "{姓名}您好！{公司}将于{日期}举办年度客户答谢会，届时有精彩活动和礼品，诚邀您参加！", category = "营销"),
                SmsTemplate(title = "发货通知", content = "尊敬的{姓名}，您在{公司}订购的商品已发货，预计3-5个工作日送达，请注意查收。", category = "通知"),
                SmsTemplate(title = "付款提醒", content = "{姓名}您好，您在{公司}有一笔{金额}元的款项待付，请于{日期}前完成支付，感谢配合！", category = "催款"),
                SmsTemplate(title = "节日问候", content = "{姓名}您好！值此佳节，{公司}全体员工祝您节日快乐，万事如意！感谢您一直以来的支持！", category = "节日"),
                SmsTemplate(title = "回访关怀", content = "{姓名}您好，感谢您选择{公司}的产品/服务。使用中如有任何问题，欢迎随时联系我们。", category = "其他"),
            )
            dao.insertAll(templates)
        }
    }
}
