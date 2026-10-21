package com.example.lastmeeting.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lastmeeting.R
import com.example.lastmeeting.data.db.MeetingEntity
import java.text.SimpleDateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToDetails: (Long) -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val meetings by viewModel.meetings.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsState()

    var selectedCalendarDate by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (recordAudioGranted) {
            viewModel.toggleRecording()
        }
    }

    fun handleRecordClick() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            viewModel.toggleRecording()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.title_meetings)) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = stringResource(R.string.title_settings)
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { handleRecordClick() },
                containerColor = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) stringResource(R.string.action_stop_recording) else stringResource(R.string.action_record)
                    )
                    if (isRecording) {
                        Spacer(modifier = Modifier.width(8.dp))
                        val mins = elapsedSeconds / 60
                        val secs = elapsedSeconds % 60
                        Text(
                            text = String.format(Locale.getDefault(), "%02d:%02d", mins, secs),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                SegmentedButton(
                    selected = viewMode == MainViewMode.LIST,
                    onClick = { viewModel.setViewMode(MainViewMode.LIST) },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.view_list)
                        )
                    }
                ) {
                    Text(text = stringResource(R.string.view_list))
                }
                SegmentedButton(
                    selected = viewMode == MainViewMode.CALENDAR,
                    onClick = { viewModel.setViewMode(MainViewMode.CALENDAR) },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CalendarMonth,
                            contentDescription = stringResource(R.string.view_calendar)
                        )
                    }
                ) {
                    Text(text = stringResource(R.string.view_calendar))
                }
            }

            if (viewMode == MainViewMode.LIST) {
                MeetingListView(
                    meetings = meetings,
                    onMeetingClick = onNavigateToDetails
                )
            } else {
                CalendarViewComponent(
                    meetings = meetings,
                    selectedDate = selectedCalendarDate,
                    onDateSelected = { selectedCalendarDate = it },
                    onMeetingClick = onNavigateToDetails
                )
            }
        }
    }
}

@Composable
fun MeetingListView(
    meetings: List<MeetingEntity>,
    onMeetingClick: (Long) -> Unit
) {
    if (meetings.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.no_meetings),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(meetings, key = { it.id }) { meeting ->
                MeetingItemCard(
                    meeting = meeting,
                    onClick = { onMeetingClick(meeting.id) }
                )
            }
        }
    }
}

@Composable
fun MeetingItemCard(
    meeting: MeetingEntity,
    onClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formattedDate = remember(meeting.startTime) { dateFormat.format(Date(meeting.startTime)) }

    val durationSeconds = ((meeting.endTime - meeting.startTime) / 1000).coerceAtLeast(0L)
    val durationText = String.format(Locale.getDefault(), "%02d:%02d", durationSeconds / 60, durationSeconds % 60)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = meeting.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun CalendarViewComponent(
    meetings: List<MeetingEntity>,
    selectedDate: String?,
    onDateSelected: (String?) -> Unit,
    onMeetingClick: (Long) -> Unit
) {
    val dayFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }

    val groupedMeetings = remember(meetings) {
        meetings.groupBy { dayFormat.format(Date(it.startTime)) }
    }

    var currentMonth by remember {
        val initialMonth = meetings.firstOrNull()?.let {
            Instant.ofEpochMilli(it.startTime).atZone(ZoneId.systemDefault()).let { zdt ->
                YearMonth.of(zdt.year, zdt.month)
            }
        } ?: YearMonth.now()
        mutableStateOf(initialMonth)
    }

    val displayedDates = remember(groupedMeetings, selectedDate) {
        if (selectedDate != null) {
            listOf(selectedDate)
        } else {
            groupedMeetings.keys.sortedDescending()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MonthCalendarCard(
            currentMonth = currentMonth,
            onPreviousMonth = { currentMonth = currentMonth.minusMonths(1) },
            onNextMonth = { currentMonth = currentMonth.plusMonths(1) },
            datesWithMeetings = groupedMeetings.keys,
            selectedDate = selectedDate,
            onDateClick = { clickedDate ->
                onDateSelected(if (selectedDate == clickedDate) null else clickedDate)
            }
        )

        if (selectedDate != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = true,
                    onClick = { onDateSelected(null) },
                    label = { Text("$selectedDate ✕") }
                )
                FilterChip(
                    selected = false,
                    onClick = { onDateSelected(null) },
                    label = { Text(stringResource(R.string.filter_all_dates)) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (meetings.isEmpty() || (selectedDate != null && groupedMeetings[selectedDate].isNullOrEmpty())) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_meetings),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(displayedDates, key = { it }) { dateStr ->
                    val meetingsForDate = groupedMeetings[dateStr] ?: emptyList()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = dateStr,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        meetingsForDate.forEach { meeting ->
                            MeetingItemCard(
                                meeting = meeting,
                                onClick = { onMeetingClick(meeting.id) }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthCalendarCard(
    currentMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    datesWithMeetings: Set<String>,
    selectedDate: String?,
    onDateClick: (String) -> Unit
) {
    val monthFormatter = remember { DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()) }
    val isoFormatter = remember { DateTimeFormatter.ISO_LOCAL_DATE }
    val daysOfWeek = remember {
        listOf(
            DayOfWeek.MONDAY,
            DayOfWeek.TUESDAY,
            DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY,
            DayOfWeek.SATURDAY,
            DayOfWeek.SUNDAY
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = null
                    )
                }
                Text(
                    text = currentMonth.format(monthFormatter),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onNextMonth) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth()) {
                for (dow in daysOfWeek) {
                    Text(
                        text = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            val firstDayOfMonth = currentMonth.atDay(1)
            val leadingEmptyCells = firstDayOfMonth.dayOfWeek.value - 1 // Monday = 0
            val daysInMonth = currentMonth.lengthOfMonth()
            val totalCells = ((leadingEmptyCells + daysInMonth + 6) / 7) * 7

            for (week in 0 until (totalCells / 7)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    for (dayIndex in 0 until 7) {
                        val cellIndex = week * 7 + dayIndex
                        val dayNumber = cellIndex - leadingEmptyCells + 1
                        if (dayNumber in 1..daysInMonth) {
                            val date: LocalDate = currentMonth.atDay(dayNumber)
                            val dateStr = date.format(isoFormatter)
                            val hasMeetings = datesWithMeetings.contains(dateStr)
                            val isSelected = selectedDate == dateStr

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.2f)
                                    .padding(2.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isSelected -> MaterialTheme.colorScheme.primary
                                            hasMeetings -> MaterialTheme.colorScheme.primaryContainer
                                            else -> MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                    .clickable { onDateClick(dateStr) },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = dayNumber.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (hasMeetings || isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isSelected -> MaterialTheme.colorScheme.onPrimary
                                            hasMeetings -> MaterialTheme.colorScheme.onPrimaryContainer
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                    if (hasMeetings) {
                                        Box(
                                            modifier = Modifier
                                                .size(4.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (isSelected) MaterialTheme.colorScheme.onPrimary
                                                    else MaterialTheme.colorScheme.primary
                                                )
                                        )
                                    }
                                }
                            }
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}
