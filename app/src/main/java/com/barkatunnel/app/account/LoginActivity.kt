package com.barkatunnel.app.account

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.BarkaApplication
import com.barkatunnel.app.R
import com.barkatunnel.app.network.ApiResult
import com.google.android.material.button.MaterialButton

class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val usernameInput = findViewById<EditText>(R.id.usernameInput)
        val passwordInput = findViewById<EditText>(R.id.passwordInput)
        val loginButton = findViewById<MaterialButton>(R.id.loginButton)

        val app = application as BarkaApplication

        val repository = AuthRepository(
            context = this,
            api = app.container.authApi,
            sessionStore = app.container.sessionStore
        )

        loginButton.setOnClickListener {
            val username = usernameInput.text.toString().trim()
            val password = passwordInput.text.toString()

            if (username.isBlank() || password.isBlank()) {
                Toast.makeText(
                    this,
                    R.string.login_fields_required,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            loginButton.isEnabled = false
            loginButton.setText(R.string.login_progress)

            Thread {
                val result = repository.login(
                    username = username,
                    password = password
                )

                runOnUiThread {
                    loginButton.isEnabled = true
                    loginButton.setText(R.string.login_button)

                    when (result) {
                        is ApiResult.Success -> {
                            if (result.data.success) {
                                Toast.makeText(
                                    this,
                                    getString(R.string.login_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                                finish()
                            } else {
                                Toast.makeText(
                                    this,
                                    result.data.message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        is ApiResult.Error -> {
                            Toast.makeText(
                                this,
                                result.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }.start()
        }
    }
}
