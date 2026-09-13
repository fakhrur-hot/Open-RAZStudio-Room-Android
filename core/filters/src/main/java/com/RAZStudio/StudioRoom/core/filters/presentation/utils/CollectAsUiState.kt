/*
 * StudioRoom is an image editor for android
 * Copyright (c) 2024 RAZStudio (Fakhrurraze)
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

package com.RAZStudio.StudioRoom.core.filters.presentation.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.RAZStudio.StudioRoom.core.filters.domain.model.Filter
import com.RAZStudio.StudioRoom.core.filters.domain.model.TemplateFilter
import com.RAZStudio.StudioRoom.core.filters.presentation.model.UiFilter
import com.RAZStudio.StudioRoom.core.filters.presentation.model.toUiFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@JvmName("collectAsUiState1")
@Composable
fun Flow<List<Filter<*>>>.collectAsUiState(): State<List<UiFilter<*>>> {
    val uiFlow = remember(this) {
        map { list -> list.map { it.toUiFilter() } }
    }
    return uiFlow.collectAsState(emptyList())
}


@Composable
fun Flow<List<TemplateFilter>>.collectAsUiState(): State<List<TemplateFilter>> {
    val sortedFlow = remember(this) {
        map { list -> list.sortedBy { it.name } }
    }
    return sortedFlow.collectAsState(emptyList())
}