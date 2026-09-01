/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:androidx.media3.common.util.ExperimentalApi

package androidx.media3.demo.composition

import android.annotation.SuppressLint
import androidx.media3.common.util.Log
import androidx.media3.common.video.AsyncFrame
import androidx.media3.common.video.Frame
import androidx.media3.common.video.FrameProcessor
import androidx.media3.common.video.FrameWriter
import androidx.media3.effect.DefaultGlFrameProcessor
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import java.util.concurrent.Executor

/** Logs frames whose content time is outside their composition item's presentation range. */
internal class FrameOutOfClipRangeLoggingFrameProcessorFactory(
  private val delegateFactory: FrameProcessor.Factory
) : FrameProcessor.Factory {

  override fun create(
    output: FrameWriter,
    listenerExecutor: Executor,
    listener: FrameProcessor.Listener,
  ): FrameProcessor =
    LoggingFrameProcessor(delegateFactory.create(output, listenerExecutor, listener))

  private class LoggingFrameProcessor(private val delegate: FrameProcessor) : FrameProcessor {

    override fun queue(frames: List<AsyncFrame>): Boolean {
      val outOfRangeFrames = frames.mapNotNull { it.frame.getOutOfRangeDetails() }
      val queued = delegate.queue(frames)
      if (queued) {
        outOfRangeFrames.forEach { details ->
          Log.w(
            TAG,
            "[PacketJourney] FrameOutOfClipRange: contentTimeUs=${details.contentTimeUs} " +
              "id=${details.mediaId} presentation=[${details.presentationRange.first}, " +
              "${details.presentationRange.last}]",
          )
        }
      }
      return queued
    }

    override fun signalEndOfStream() = delegate.signalEndOfStream()

    override fun close() = delegate.close()
  }

  private companion object {
    const val TAG = "CompPreviewVM"
  }
}

private data class OutOfRangeDetails(
  val contentTimeUs: Long,
  val mediaId: String,
  val presentationRange: LongRange,
)

private fun Frame.getOutOfRangeDetails(): OutOfRangeDetails? {
  val presentationRange = getPresentationRange() ?: return null
  return if (contentTimeUs !in presentationRange) {
    OutOfRangeDetails(contentTimeUs, getMediaId(), presentationRange)
  } else {
    null
  }
}

private fun Frame.getMediaId(): String {
  val composition = metadata[Composition.KEY_COMPOSITION] as Composition
  val sequenceIndex = metadata[DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX] as Int
  val itemIndex = metadata[Composition.KEY_COMPOSITION_ITEM_INDEX] as Int
  return composition.sequences[sequenceIndex].editedMediaItems[itemIndex].mediaItem.mediaId
}

private fun Frame.getPresentationRange(): LongRange? = runCatching {
  val composition = metadata[Composition.KEY_COMPOSITION] as Composition
  val sequenceIndex = metadata[DefaultGlFrameProcessor.KEY_COMPOSITION_SEQUENCE_INDEX] as Int
  val itemIndex = metadata[Composition.KEY_COMPOSITION_ITEM_INDEX] as Int
  val starts = composition.sequences[sequenceIndex].presentationStartTimesUs()
  starts[itemIndex] until starts[itemIndex + 1]
}.getOrNull()

@SuppressLint("RestrictedApi")
private fun EditedMediaItemSequence.presentationStartTimesUs(): LongArray {
  val items = editedMediaItems
  val starts = LongArray(items.size + 1)
  for (i in items.indices) {
    starts[i + 1] = starts[i] + items[i].presentationDurationUs
  }
  return starts
}
