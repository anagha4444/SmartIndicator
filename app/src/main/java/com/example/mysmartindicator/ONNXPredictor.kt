import android.content.Context
import ai.onnxruntime.*
import java.nio.FloatBuffer

object ONNXPredictor {
    private lateinit var session: OrtSession
    private lateinit var env: OrtEnvironment

    fun loadModel(context: Context) {
        env = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open("indicator_model.onnx").readBytes()
        session = env.createSession(modelBytes)
    }

    fun predict(
        lat: Float,
        lng: Float,
        speed: Float,
        directionCode: Float,
        distance: Float
    ): Boolean {
        val inputName = session.inputNames.iterator().next()

        val inputBuffer = FloatBuffer.allocate(5)
        inputBuffer.put(lat)
        inputBuffer.put(lng)
        inputBuffer.put(speed)
        inputBuffer.put(directionCode)
        inputBuffer.put(distance)
        inputBuffer.rewind()

        val inputTensor = OnnxTensor.createTensor(env, inputBuffer, longArrayOf(1, 5))
        val results = session.run(mapOf(inputName to inputTensor))
        // ✅ RandomForest ONNX outputs labels as int64 → LongArray
        val output = results[0].value as LongArray

        // Return true if prediction == 1
        return output[0] == 1L// True = "likely to forget indicator"
    }
}
