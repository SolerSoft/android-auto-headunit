package info.anodsplace.headunit.aap.protocol.messages

import android.view.MotionEvent
import com.google.protobuf.MessageLite
import info.anodsplace.headunit.aap.AapMessage
import info.anodsplace.headunit.aap.protocol.Channel
import info.anodsplace.headunit.aap.protocol.proto.Input

class TouchEvent(timeStamp: Long, action: Input.TouchEvent.PointerAction, pointerId: Int, x: Int, y: Int) : AapMessage(Channel.ID_INP, Input.InputMsgType.EVENT_VALUE, makeProto(timeStamp, action, pointerId, x, y)) {

    companion object {
        fun motionEventToAction(event: Int): Input.TouchEvent.PointerAction? {
            return when (event) {
                MotionEvent.ACTION_POINTER_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_DOWN
                MotionEvent.ACTION_POINTER_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_POINTER_UP
                MotionEvent.ACTION_DOWN -> Input.TouchEvent.PointerAction.TOUCH_ACTION_DOWN
                MotionEvent.ACTION_UP -> Input.TouchEvent.PointerAction.TOUCH_ACTION_UP
                MotionEvent.ACTION_MOVE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_MOVE
                MotionEvent.ACTION_CANCEL -> Input.TouchEvent.PointerAction.TOUCH_ACTION_CANCEL
                MotionEvent.ACTION_OUTSIDE -> Input.TouchEvent.PointerAction.TOUCH_ACTION_OUTSIDE
                else -> null
            }
        }

        private fun makeProto(timeStamp: Long, action: Input.TouchEvent.PointerAction, pointerId: Int, x: Int, y: Int): MessageLite {
            val touchEvent = Input.TouchEvent.newBuilder()
                    .also {
                        it.addPointerData(
                                Input.TouchEvent.Pointer.newBuilder().also { pointer ->
                                    pointer.x = x
                                    pointer.y = y
                                    pointer.pointerId = pointerId
                                })
                        it.action = action
                    }

            return Input.InputReport.newBuilder()
                    .setTimestamp(timeStamp * 1000000L)
                    .setTouchEvent(touchEvent).build()
        }
    }
}
