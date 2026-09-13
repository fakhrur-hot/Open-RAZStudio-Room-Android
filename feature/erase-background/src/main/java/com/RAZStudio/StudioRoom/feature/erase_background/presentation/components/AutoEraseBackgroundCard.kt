/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2026 RAZStudio (Fakhrurraze)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/LICENSE-2.0>.
 */

package com.RAZStudio.StudioRoom.feature.erase_background.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.domain.utils.Flavor
import com.RAZStudio.StudioRoom.core.domain.utils.ListUtils.toggle
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.AutoFixHigh
import com.RAZStudio.StudioRoom.core.resources.icons.SettingsBackupRestore
import com.RAZStudio.StudioRoom.core.ui.theme.mixedContainer
import com.RAZStudio.StudioRoom.core.ui.theme.onMixedContainer
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButtonGroup
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.hapticsClickable
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.ShapeDefaults
import com.RAZStudio.StudioRoom.core.ui.widget.modifier.container
import com.RAZStudio.StudioRoom.feature.erase_background.domain.model.BgModelType

@Composable
fun AutoEraseBackgroundCard(
    modifier: Modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    onClick: (BgModelType) -> Unit,
    onReset: () -> Unit
) {
    var selectedModel by rememberSaveable {
        mutableStateOf(flavoredEntries.first())
    }

    Column(
        Modifier
            .then(modifier)
            .container(
                resultPadding = 8.dp,
                shape = ShapeDefaults.extraLarge
            )
    ) {
        if (flavoredEntries.size > 1) {
            EnhancedButtonGroup(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                entries = flavoredEntries,
                value = selectedModel,
                title = null,
                onValueChange = { selectedModel = it },
                itemContent = { type -> Text(text = type.title) },
                isScrollable = true,
                activeButtonColor = MaterialTheme.colorScheme.secondaryContainer
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier
                .container(
                    resultPadding = 0.dp,
                    color = MaterialTheme.colorScheme.mixedContainer.copy(0.7f)
                )
                .hapticsClickable(
                    onClick = { onClick(selectedModel) }
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(id = R.string.auto_erase_background),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onMixedContainer
            )
            Icon(
                imageVector = Icons.Rounded.AutoFixHigh,
                contentDescription = stringResource(R.string.auto_erase_background),
                tint = MaterialTheme.colorScheme.onMixedContainer
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        EnhancedButton(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(0.2f),
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            shape = ShapeDefaults.default,
            isShadowClip = true
        ) {
            Icon(
                imageVector = Icons.Rounded.SettingsBackupRestore,
                contentDescription = null
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(id = R.string.restore_image))
        }
    }
}

private val flavoredEntries: List<BgModelType> = BgModelType.entries.let {
    if (Flavor.isFoss()) it.toggle(BgModelType.MlKit)
    else it
}
