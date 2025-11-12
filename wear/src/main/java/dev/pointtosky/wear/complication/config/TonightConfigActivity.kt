package dev.pointtosky.wear.complication.config

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

class TonightConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Tonight Target – config (stub)" })
            addView(Button(context).apply {
                text = "Save"
                setOnClickListener {
                    setResult(RESULT_OK)
                    finish()
                }
            })
        }
        setContentView(root)
    }
}
