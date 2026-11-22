package moe.shizuku.manager.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.BootLogActivityBinding
import moe.shizuku.manager.utils.BootLog

class BootLogActivity : AppBarActivity() {

    private lateinit var binding: BootLogActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding = BootLogActivityBinding.inflate(layoutInflater, rootView, true)
        show()
    }

    private fun show() {
        val text = BootLog.readTail(this)
        binding.logText.text = text.ifEmpty { getString(R.string.boot_log_empty) }
        binding.scrollView.post { binding.scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.boot_log, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_boot_log_share -> {
                share()
                true
            }
            R.id.action_boot_log_copy -> {
                copy()
                true
            }
            R.id.action_boot_log_clear -> {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.boot_log_clear_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        BootLog.clear(this)
                        show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun redactedLog(): String = BootLog.redact(BootLog.readTail(this))

    private fun copy() {
        val text = redactedLog()
        if (text.isEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("boot log", text.lines().takeLast(300).joinToString("\n")))
        Toast.makeText(this, R.string.boot_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val text = redactedLog()
        if (text.isEmpty()) return
        val file = File(cacheDir, "boot_log.txt").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("boot log", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.boot_log_share)))
    }
}
