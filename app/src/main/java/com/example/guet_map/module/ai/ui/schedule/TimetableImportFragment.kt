package com.example.guet_map.module.ai.ui.schedule

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.guet_map.R
import com.example.guet_map.databinding.FragmentTimetableImportBinding
import com.example.guet_map.module.ai.data.model.TimetableEntry
import com.example.guet_map.ui.MainNavViewModel
import com.example.guet_map.ui.map.MapViewModel
import com.example.guet_map.util.ClassroomCodeParser
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TimetableImportFragment : Fragment() {

    private var _binding: FragmentTimetableImportBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TimetableImportViewModel by viewModels()
    private val mainNavViewModel: MainNavViewModel by activityViewModels()
    private val mapViewModel: MapViewModel by activityViewModels()
    private lateinit var entriesAdapter: TimetableEntriesAdapter

    private var semesterList: List<String> = emptyList()
    private var isSpinnerInitialized = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableImportBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupSpinners()
        setupRecyclerView()
        setupAddButton()
        observeViewModel()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupSpinners() {
        // 动态生成学期列表（当前年份往前3年，往后1年）
        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val semesters = mutableListOf<String>()
        for (year in (currentYear + 1) downTo (currentYear - 3)) {
            semesters.add("$year-1（${year}年秋季）")
            semesters.add("$year-2（${year}年春季）")
        }
        semesterList = semesters.map { it.substringBefore("（") }

        binding.spinnerSemester.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            semesters
        )
        binding.spinnerSemester.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isSpinnerInitialized) {
                    viewModel.setSemester(semesterList[position])
                }
                isSpinnerInitialized = true
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // 星期 Spinner
        val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        binding.spinnerDayOfWeek.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            days
        )

        // 节次 Spinner（1-14节）
        val periods = (1..14).map { "第${it}节" }
        binding.spinnerStartPeriod.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            periods
        )
        binding.spinnerEndPeriod.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            periods
        )
        // 默认选择第1节和第2节
        binding.spinnerStartPeriod.setSelection(0)
        binding.spinnerEndPeriod.setSelection(1)
    }

    private fun setupRecyclerView() {
        entriesAdapter = TimetableEntriesAdapter(
            onNavigate = { entry -> navigateToLocation(entry) },
            onEdit = { entry -> showEditDialog(entry) },
            onDelete = { entry -> viewModel.deleteEntry(entry) }
        )
        binding.recyclerViewEntries.apply {
            adapter = entriesAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }
    }

    private fun navigateToLocation(entry: TimetableEntry) {
        val classroomCode = entry.classroomName
        val locations = mapViewModel.cachedLocations.value

        // 尝试解析教室代码（如 07101 → 7教）
        val target = ClassroomCodeParser.resolveNavigationTarget(classroomCode, locations)

        when {
            // 方案1：有 locationId，直接使用
            target?.locationId != null -> {
                mainNavViewModel.openLocationOnMap(target.locationId)
                Toast.makeText(
                    requireContext(),
                    "正在导航到 ${target.buildingName}（${classroomCode}）",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // 方案2：没有精确匹配的教学楼位置，尝试用教学楼名称搜索
            target?.buildingName != null -> {
                mainNavViewModel.openLocationOnMap(target.buildingName)
                Toast.makeText(
                    requireContext(),
                    "正在搜索 ${target.buildingName}（${classroomCode}）",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // 方案3：无法解析教室代码，直接使用教室名称搜索
            else -> {
                mainNavViewModel.openLocationOnMap(classroomCode)
                Toast.makeText(requireContext(), "正在搜索 $classroomCode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEditDialog(entry: TimetableEntry) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_timetable, null)
        val editCourseName = dialogView.findViewById<android.widget.EditText>(R.id.editCourseName)
        val editClassroom = dialogView.findViewById<android.widget.EditText>(R.id.editClassroom)
        val spinnerDay = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerDayOfWeek)
        val spinnerStart = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerStartPeriod)
        val spinnerEnd = dialogView.findViewById<android.widget.Spinner>(R.id.spinnerEndPeriod)
        val editWeekRange = dialogView.findViewById<android.widget.EditText>(R.id.editWeekRange)

        // 填充当前数据
        editCourseName.setText(entry.courseName)
        editClassroom.setText(entry.classroomName)
        editWeekRange.setText(entry.weekRange)

        // 设置星期
        val days = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        spinnerDay.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, days)
        spinnerDay.setSelection(entry.dayOfWeek - 1)

        // 设置节次
        val periods = (1..14).map { "第${it}节" }
        spinnerStart.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, periods)
        spinnerEnd.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, periods)
        spinnerStart.setSelection(entry.startPeriod - 1)
        spinnerEnd.setSelection(entry.endPeriod - 1)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("修改课程")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val newCourseName = editCourseName.text.toString()
                val newClassroom = editClassroom.text.toString()
                val newDayOfWeek = spinnerDay.selectedItemPosition + 1
                val newStartPeriod = spinnerStart.selectedItemPosition + 1
                val newEndPeriod = spinnerEnd.selectedItemPosition + 1
                val newWeekRange = editWeekRange.text.toString()

                if (newCourseName.isBlank()) {
                    Toast.makeText(requireContext(), "课程名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newClassroom.isBlank()) {
                    Toast.makeText(requireContext(), "教室不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newStartPeriod > newEndPeriod) {
                    Toast.makeText(requireContext(), "开始节次不能大于结束节次", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                viewModel.updateEntry(
                    entry = entry,
                    courseName = newCourseName.trim(),
                    classroomName = newClassroom.trim(),
                    dayOfWeek = newDayOfWeek,
                    startPeriod = newStartPeriod,
                    endPeriod = newEndPeriod,
                    weekRange = newWeekRange.trim().ifBlank { "1-16" }
                )
                Toast.makeText(requireContext(), "课程修改成功", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setupAddButton() {
        binding.buttonAddEntry.setOnClickListener {
            val selectedPosition = binding.spinnerSemester.selectedItemPosition
            if (selectedPosition < 0 || semesterList.isEmpty()) {
                Toast.makeText(requireContext(), "请选择学期", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val semester = semesterList[selectedPosition]
            val courseName = binding.editCourseName.text.toString()
            val classroom = binding.editClassroom.text.toString()
            val dayOfWeek = binding.spinnerDayOfWeek.selectedItemPosition + 1
            val startPeriod = binding.spinnerStartPeriod.selectedItemPosition + 1
            val endPeriod = binding.spinnerEndPeriod.selectedItemPosition + 1
            val weekRange = binding.editWeekRange.text.toString()

            viewModel.saveEntry(
                courseName = courseName,
                classroomName = classroom,
                dayOfWeek = dayOfWeek,
                startPeriod = startPeriod,
                endPeriod = endPeriod,
                weekRange = weekRange,
                semester = semester
            )
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.semester.collect { currentSemester ->
                        val index = semesterList.indexOf(currentSemester)
                        if (index >= 0 && binding.spinnerSemester.selectedItemPosition != index) {
                            binding.spinnerSemester.setSelection(index)
                        }
                    }
                }

                launch {
                    viewModel.entries.collect { entries ->
                        entriesAdapter.submitList(entries)
                        binding.textEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.events.collect { event ->
                        when (event) {
                            is TimetableImportViewModel.ImportEvent.SaveSuccess -> {
                                Toast.makeText(requireContext(), "课程添加成功", Toast.LENGTH_SHORT).show()
                                clearForm()
                            }
                            is TimetableImportViewModel.ImportEvent.ValidationError -> {
                                Toast.makeText(requireContext(), event.message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun clearForm() {
        binding.editCourseName.text?.clear()
        binding.editClassroom.text?.clear()
        binding.editWeekRange.text?.clear()
        binding.spinnerDayOfWeek.setSelection(0)
        binding.spinnerStartPeriod.setSelection(0)
        binding.spinnerEndPeriod.setSelection(1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
