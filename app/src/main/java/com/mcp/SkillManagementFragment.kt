package com.mcp

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.mcp.core.skill.MarkdownSkill
import com.mcp.core.skill.SkillRepository
import com.mcp.core.skill.SkillPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SkillManagementFragment : Fragment(R.layout.fragment_skill_management) {

    companion object {
        const val PREF_SKILL_ENABLED = SkillPreferences.PREF_SKILL_ENABLED
        fun isSkillEnabled(ctx: Context, name: String): Boolean =
            SkillPreferences.isEnabled(ctx, name)
        fun setSkillEnabled(ctx: Context, name: String, enabled: Boolean) =
            SkillPreferences.setEnabled(ctx, name, enabled)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val safPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) scope.launch { importFromUri(uri) }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        refresh()
        view.findViewById<MaterialButton>(R.id.btnSkillBack).setOnClickListener { parentFragmentManager.popBackStack() }
        view.findViewById<MaterialButton>(R.id.btnSkillAdd).setOnClickListener { safPicker.launch(null) }
    }

    private fun refresh() {
        val ctx = requireContext()
        // 刷新列表时同步重扫一次，让手动移动到 files/skills 的技能也能注册脚本工具
        com.mcp.composition.ToolRuntimeHolder.rescanSkills()
        val repo = SkillRepository(ctx).also { it.discover() }
        val allSkills = repo.allSkills()
        val userNames = File(ctx.filesDir, "skills")
            .let { d -> if (d.isDirectory()) d.listFiles { f -> f.isDirectory }?.map { it.name }?.toSet() ?: emptySet() else emptySet() }

        val rv = requireView().findViewById<RecyclerView>(R.id.rvSkills)
        val tvEmpty = requireView().findViewById<TextView>(R.id.tvSkillEmpty)
        if (allSkills.isEmpty()) { tvEmpty.visibility = View.VISIBLE }
        else {
            tvEmpty.visibility = View.GONE
            rv.adapter = SkillAdapter(allSkills, userNames, ctx, parentFragmentManager,
                onDelete = { name -> deleteSkill(name) }
            )
        }
    }

    private suspend fun importFromUri(treeUri: Uri) {
        val ctx = requireContext()
        val rootDoc = DocumentFile.fromTreeUri(ctx, treeUri) ?: run {
            Toast.makeText(ctx, "无法访问选择的目录", Toast.LENGTH_SHORT).show(); return
        }
        val dirName = rootDoc.name ?: "imported_skill"
        val (mdFiles, fileList) = withContext(Dispatchers.IO) {
            val files = rootDoc.listFiles().toList()
            val mds = files.filter { it.name?.endsWith(".md") == true && it.isFile }
            Pair(mds, files)
        }
        if (mdFiles.isEmpty()) {
            Toast.makeText(ctx, "所选目录中没有 .md 文件，无法导入技能", Toast.LENGTH_LONG).show()
            return
        }
        val (copiedCount, targetDir) = withContext(Dispatchers.IO) {
            val dir = File(ctx.filesDir, "skills/$dirName")
            if (dir.exists()) { return@withContext Pair(0, dir) }
            dir.mkdirs()
            var count = 0
            // 递归复制整个技能目录：不仅顶层 .md / skill.json，还包括 scripts/ 等子目录。
            // 否则脚本工具（skill_<名>_<脚本>）因找不到脚本文件而无法注册/执行。
            fun copyDir(source: DocumentFile, dest: File) {
                for (doc in source.listFiles()) {
                    if (doc.isFile) {
                        val name = doc.name ?: continue
                        try {
                            ctx.contentResolver.openInputStream(doc.uri)?.use { input ->
                                File(dest, name).outputStream().use { out -> input.copyTo(out) }
                            }
                            count++
                        } catch (_: Exception) { }
                    } else if (doc.isDirectory) {
                        val sub = File(dest, doc.name ?: continue)
                        sub.mkdirs()
                        copyDir(doc, sub)
                    }
                }
            }
            copyDir(rootDoc, dir)
            val metaFile = File(dir, "skill.json")
            if (!metaFile.exists()) {
                val desc = mdFiles.firstOrNull()?.let { getFileDescription(it.name ?: "") } ?: ""
                metaFile.writeText("""{"name":"$dirName","description":"$desc","version":"1.0.0"}""")
            }
            // 新导入的技能默认启用
            setSkillEnabled(ctx, dirName, true)
            Pair(count, dir)
        }
        if (copiedCount == 0) return
        Toast.makeText(ctx, "已导入技能「$dirName」（${copiedCount} 个文件，${mdFiles.size} 个 .md）", Toast.LENGTH_LONG).show()
        // 导入后重扫并注册脚本工具（否则 skill_<名>_<脚本> 不会出现在工具清单里）
        com.mcp.composition.ToolRuntimeHolder.rescanSkills()
        refresh()
    }

    private fun getFileDescription(fileName: String): String =
        fileName.removeSuffix(".md").replace("_", " ").replace("-", " ").trim()

    private fun deleteSkill(name: String) {
        val ctx = requireContext()
        MaterialDialogs.confirmDestructive(
            context = ctx,
            title = "删除技能",
            message = "将删除「" + name + "」及其全部文件，并注销对应脚本工具。此操作不可恢复。",
            confirmText = "删除",
        ) {
            File(ctx.filesDir, "skills/$name").deleteRecursively()
            setSkillEnabled(ctx, name, false)
            // 删除后注销对应脚本工具
            com.mcp.composition.ToolRuntimeHolder.rescanSkills()
            Toast.makeText(ctx, "已删除", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    override fun onDestroyView() { super.onDestroyView(); scope.cancel() }
}

private class SkillAdapter(
    private val items: List<MarkdownSkill>,
    private val userNames: Set<String>,
    private val ctx: android.content.Context,
    private val fragmentManager: androidx.fragment.app.FragmentManager,
    private val onDelete: (String) -> Unit
) : RecyclerView.Adapter<SkillVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SkillVH =
        SkillVH(LayoutInflater.from(parent.context).inflate(R.layout.item_skill, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: SkillVH, position: Int) {
        val skill = items[position]
        val isUser = userNames.contains(skill.name)
        holder.tvName.text = skill.name
        holder.tvVersion.text = "v" + skill.version
        holder.tvSource.text = if (isUser) "● 用户导入" else "● 内置（只读）"
        holder.tvSource.setTextColor(
            if (isUser) ctx.getColor(android.R.color.holo_green_dark)
            else ctx.getColor(android.R.color.darker_gray)
        )
        // 描述优先取 skill.json 的 description；为空则用文件名兜底
        val desc = skill.description.ifBlank { "" }
        holder.tvDesc.text = desc
        holder.tvDesc.visibility = if (desc.isBlank()) View.GONE else View.VISIBLE
        holder.tvFiles.text = skill.files.joinToString(" · ")
        holder.tvFiles.visibility = if (skill.files.isNotEmpty()) View.VISIBLE else View.GONE

        // 启用/禁用滑块
        val isEnabled = SkillManagementFragment.isSkillEnabled(ctx, skill.name)
        holder.switchEnabled.isChecked = isEnabled
        holder.switchEnabled.setOnCheckedChangeListener { _, checked ->
            SkillManagementFragment.setSkillEnabled(ctx, skill.name, checked)
        }

        holder.btnView.setOnClickListener {
            // 用 Markdown 渲染器替代裸文本弹窗：标题/列表/表格/代码块正常呈现，
            // 且视觉与应用的 Material3 风格一致。
            MarkdownViewerDialog.newInstance(
                title = skill.name,
                version = skill.version,
                subtitle = skill.files.joinToString(" · "),
                content = skill.content,
            ).show(fragmentManager, "SKILL_VIEWER")
        }
        if (isUser) {
            holder.btnDelete.visibility = View.VISIBLE
            holder.btnDelete.setOnClickListener { onDelete(skill.name) }
        } else {
            holder.btnDelete.visibility = View.GONE
        }
    }
}

private class SkillVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
    val tvName: TextView = itemView.findViewById(R.id.tvSkillName)
    val tvVersion: TextView = itemView.findViewById(R.id.tvSkillVersion)
    val tvSource: TextView = itemView.findViewById(R.id.tvSkillSource)
    val tvDesc: TextView = itemView.findViewById(R.id.tvSkillDesc)
    val tvFiles: TextView = itemView.findViewById(R.id.tvSkillFiles)
    val switchEnabled: MaterialSwitch = itemView.findViewById(R.id.switchSkillEnabled)
    val btnView: MaterialButton = itemView.findViewById(R.id.btnSkillView)
    val btnDelete: MaterialButton = itemView.findViewById(R.id.btnSkillDelete)
}