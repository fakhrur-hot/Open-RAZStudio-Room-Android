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

package com.RAZStudio.StudioRoom.core.filters.presentation.widget

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import com.RAZStudio.StudioRoom.core.resources.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.RAZStudio.StudioRoom.core.resources.R
import com.RAZStudio.StudioRoom.core.resources.icons.AutoFixHigh
import com.RAZStudio.StudioRoom.core.resources.icons.Extension
import com.RAZStudio.StudioRoom.core.ui.theme.mixedContainer
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedButton
import com.RAZStudio.StudioRoom.core.ui.widget.enhanced.EnhancedIconButton

@Composable
fun AddFilterButton(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.mixedContainer,
    onClick: () -> Unit,
    onCreateTemplate: (() -> Unit)? = null
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        onCreateTemplate?.let {
            EnhancedIconButton(
                onClick = onCreateTemplate,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Icon(
                    imageVector = Icons.Rounded.Extension,
                    contentDescription = "extension"
                )
            }
        }

        EnhancedButton(
            containerColor = containerColor,
            onClick = onClick
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoFixHigh,
                contentDescription = stringResource(R.string.add_filter)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(id = R.string.add_filter))
        }
    }
}