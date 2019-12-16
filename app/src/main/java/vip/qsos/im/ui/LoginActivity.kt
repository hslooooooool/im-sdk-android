package vip.qsos.im.ui

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.View.OnClickListener
import kotlinx.android.synthetic.main.activity_login.*
import vip.qsos.im.app.AbsIMActivity
import vip.qsos.im.app.Constant
import vip.qsos.im.demo.R
import vip.qsos.im.lib.IMManagerHelper
import vip.qsos.im.lib.constant.IMConstant
import vip.qsos.im.lib.model.ReplyBody

/**
 * @author : 华清松
 * 登录页面
 */
class LoginActivity : AbsIMActivity(), OnClickListener {

    private lateinit var progressDialog: ProgressDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        initViews()
    }

    private fun initViews() {
        progressDialog = ProgressDialog(this)
        progressDialog.setTitle("提示")
        progressDialog.setMessage("正在登录，请稍候......")
        progressDialog.setCancelable(true)
        progressDialog.setCanceledOnTouchOutside(false)
        login.setOnClickListener(this)
    }

    private fun doLogin() {
        val account = account_1.text.toString().trim()
        if (!TextUtils.isEmpty(account)) {
            progressDialog.show()
            if (IMManagerHelper.isConnected(this)) {
                IMManagerHelper.bindAccount(this, account)
            } else {
                IMManagerHelper.connect(this, Constant.IM_SERVER_HOST, Constant.IM_SERVER_PORT)
            }
        }
    }

    override fun onConnectionSuccess(hasAutoBind: Boolean) {
        if (!hasAutoBind) {
            IMManagerHelper.bindAccount(this, account_1.text.toString().trim { it <= ' ' })
        }
    }

    override fun onReplyReceived(replyBody: ReplyBody) {
        progressDialog.dismiss()
        /*收到code为200的回应 账号绑定成功*/
        if (replyBody.key == IMConstant.RequestKey.CLIENT_BIND && replyBody.code == IMConstant.ReturnCode.CODE_200) {
            val intent = Intent(this, MessageActivity::class.java)
            intent.putExtra("account", account_1.text.toString().trim { it <= ' ' })
            startActivity(intent)
            this.finish()
        }
    }

    override fun onClick(v: View) {
        doLogin()
    }

    override fun onBackPressed() {
        IMManagerHelper.destroy(this)
        super.onBackPressed()
    }

}
