package moe.shizuku.manager.settings

import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.BuildConfig
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.BugReportDialogBinding
import moe.shizuku.manager.ktx.applyTemplateArgs
import moe.shizuku.manager.ktx.asLink
import moe.shizuku.manager.utils.CustomTabsHelper
import moe.shizuku.manager.worker.AdbStartWorker

class BugReportDialog : DialogFragment() {

    private lateinit var binding: BugReportDialogBinding

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        binding = BugReportDialogBinding.inflate(layoutInflater)

        val repoUrl = "https://github.com/${getString(R.string.repo_slug)}"

        binding.apply {
            updateText.applyTemplateArgs(
                getString(R.string.bug_report_dialog_link_update).asLink("$repoUrl/releases/latest")
            )
            issuesText.applyTemplateArgs(
                getString(R.string.bug_report_dialog_link_issues).asLink("$repoUrl/issues")
            )
            methodText.applyTemplateArgs("GitHub")
        }

        return MaterialAlertDialogBuilder(context)
            .setTitle(R.string.settings_report_bug)
            .setView(binding.root)
            .setPositiveButton("GitHub") { _, _ ->
                val body = """
                    Please describe the bug. Include steps to reproduce if possible, as well as any relevant images/logs.
                    If it is about starting at boot, attach the boot log (home menu > Boot log > Share).

                    Device: ${Build.MANUFACTURER} ${Build.MODEL}
                    Android Version: ${Build.VERSION.RELEASE}
                    ${getString(R.string.app_name)} Version: ${BuildConfig.VERSION_NAME}
                """.trimIndent()
                CustomTabsHelper.launchUrlOrCopy(context, "$repoUrl/issues/new?body=${Uri.encode(body)}")
            }
            .setNeutralButton(android.R.string.cancel) { dialog, _ ->
                dialog.cancel()
            }
            .create()
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AdbStartWorker.NOTIFICATION_ID)
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (activity is BugReportDialogActivity) activity?.finish()
    }
}
