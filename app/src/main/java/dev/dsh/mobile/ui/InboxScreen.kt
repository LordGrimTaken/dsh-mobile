package dev.dsh.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.dsh.mobile.data.AppState
import dev.dsh.mobile.data.Approval
import dev.dsh.mobile.data.MAX_QUESTION_ANSWER_CHARS
import dev.dsh.mobile.data.Question
import dev.dsh.mobile.data.normalizeQuestionAnswer
import dev.dsh.mobile.ui.theme.Warning

/** Triage surface for every pending approval and question across sessions. */
@Composable
fun InboxScreen(state: AppState, onBack: () -> Unit) {
    val approvals = state.inboxApprovals
    val questions = state.inboxQuestions
    val approvalsReadOnly = state.approvalsReadOnly
    val questionsReadOnly = state.questionsReadOnly
    val openSession: (String) -> Unit = { sessionId ->
        state.closeInbox()
        state.openSession(sessionId)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "INBOX",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${approvals.size + questions.size} pending",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(14.dp))
        }

        // First-answer-wins / read-only status: brief copy instead of a raw error, dismissible.
        state.syncError?.let { message ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Dismiss",
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable(onClick = state::clearSyncError)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
        }

        if (approvals.isEmpty() && questions.isEmpty()) {
            val hostSupportsBridge = state.approvalsSupported || state.questionsSupported
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    if (hostSupportsBridge) {
                        "Nothing needs approval right now."
                    } else {
                        "Approvals aren't available from this host yet."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        hostSupportsBridge && approvalsReadOnly && questionsReadOnly ->
                            "This host exposes approvals and questions read-only; resolve them " +
                                "on the desktop."

                        hostSupportsBridge && approvalsReadOnly ->
                            "This host exposes approvals read-only, so resolve pending approvals " +
                                "on the desktop. Questions can still be answered here."

                        hostSupportsBridge && questionsReadOnly ->
                            "This host exposes questions read-only, so answer them on the desktop. " +
                                "Approvals can still be handled here."

                        hostSupportsBridge ->
                            "Approvals and questions from every session will appear here."

                        else ->
                            "This host plugin doesn't expose its approval/question bridge yet, " +
                                "so resolve them on the desktop. They'll appear here once the host " +
                                "supports it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 6.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (approvals.isNotEmpty()) {
                item(key = "approvals-header") {
                    HadalCaps("Approvals", modifier = Modifier.padding(start = 2.dp, top = 6.dp))
                }
                if (approvalsReadOnly) {
                    item(key = "approvals-readonly") {
                        Text(
                            "read-only on this host · resolve on the desktop",
                            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                items(approvals, key = { "approval:${it.id}" }) { approval ->
                    InboxApprovalCard(
                        approval = approval,
                        sessionTitle = sessionTitle(state, approval.sessionId),
                        readOnly = approvalsReadOnly,
                        onAllow = { state.answerApprovalFor(approval.id, true) },
                        onDeny = { state.answerApprovalFor(approval.id, false) },
                        onOpenSession = { openSession(approval.sessionId) },
                    )
                }
            }
            if (questions.isNotEmpty()) {
                item(key = "questions-header") {
                    HadalCaps("Questions", modifier = Modifier.padding(start = 2.dp, top = 8.dp))
                }
                if (questionsReadOnly) {
                    item(key = "questions-readonly") {
                        Text(
                            "read-only on this host · answer on the desktop",
                            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                items(questions, key = { "question:${it.id}" }) { question ->
                    InboxQuestionCard(
                        question = question,
                        sessionTitle = sessionTitle(state, question.sessionId),
                        readOnly = questionsReadOnly,
                        answering = state.isQuestionAnswering(question.id),
                        onAnswer = { answer, index ->
                            state.answerQuestionFor(question.id, answer, index)
                        },
                        onOpenSession = { openSession(question.sessionId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxApprovalCard(
    approval: Approval,
    sessionTitle: String,
    readOnly: Boolean,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onOpenSession: () -> Unit,
) {
    HadalCard {
        Text(
            sessionTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            approval.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            approval.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            approval.command,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = Warning,
            maxLines = 3,
        )
        Spacer(Modifier.height(10.dp))
        if (readOnly) {
            Text(
                "Resolve on desktop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "This host keeps approvals on the desktop, so it can't be answered from the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HadalPrimaryButton(
                    text = "Allow once",
                    onClick = onAllow,
                    modifier = Modifier.weight(1f),
                )
                HadalGhostButton(
                    text = "Deny",
                    onClick = onDeny,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Open session",
            modifier = Modifier
                .clickable(onClick = onOpenSession)
                .padding(vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun InboxQuestionCard(
    question: Question,
    sessionTitle: String,
    readOnly: Boolean,
    answering: Boolean,
    onAnswer: (String, Int?) -> Unit,
    onOpenSession: () -> Unit,
) {
    // Input state is keyed by the question id so a different question starts blank.
    var otherOpen by remember(question.id) { mutableStateOf(false) }
    var draft by remember(question.id) { mutableStateOf("") }
    val canSend = !answering && normalizeQuestionAnswer(draft) != null
    HadalCard {
        Text(
            sessionTitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Spacer(Modifier.height(5.dp))
        Text(
            question.prompt,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (readOnly) {
            question.options.forEachIndexed { index, option ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "${index + 1}. $option",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Answer on desktop",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "This host keeps questions on the desktop, so it can't be answered from the phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        } else {
            if (question.options.isNotEmpty()) {
                question.options.forEachIndexed { index, option ->
                    Spacer(Modifier.height(7.dp))
                    HadalGhostButton(
                        text = option,
                        onClick = { onAnswer(option, index) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (otherOpen) "Hide custom answer" else "Other…",
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .clickable { otherOpen = !otherOpen }
                        .padding(vertical = 3.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (question.options.isEmpty() || otherOpen) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(MAX_QUESTION_ANSWER_CHARS) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !answering,
                    placeholder = {
                        Text(
                            "Type an answer…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    minLines = 1,
                    maxLines = 3,
                    shape = RoundedCornerShape(8.dp),
                )
                Spacer(Modifier.height(6.dp))
                HadalPrimaryButton(
                    text = if (answering) "Sending…" else "Send",
                    onClick = {
                        val answer = normalizeQuestionAnswer(draft)
                        if (answer != null && !answering) {
                            onAnswer(answer, null)
                            draft = ""
                            otherOpen = false
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (question.options.isEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Free-text question · answers map to a custom response.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Open session",
            modifier = Modifier
                .clickable(onClick = onOpenSession)
                .padding(vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

private fun sessionTitle(state: AppState, sessionId: String): String =
    state.sessions.firstOrNull { it.id == sessionId }?.title
        ?: state.visibleSessions().firstOrNull { it.id == sessionId }?.title
        ?: "session"
