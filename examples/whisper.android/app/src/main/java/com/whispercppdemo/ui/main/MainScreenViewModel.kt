package com.whispercppdemo.ui.main

import android.app.Application
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.whispercppdemo.media.decodeWaveFile
import com.whispercppdemo.recorder.Recorder
import com.whispercppdemo.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.client.engine.android.Android
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.kotlinx.serializer.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.contentnegotiation.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

//import ktor
//import io.ktor.client.HttpClient
//import io.ktor.client.engine.android.Android
//import io.ktor.client.features.json.JsonFeature
//import io.ktor.client.features.json.serializer.KotlinxSerializer
//import io.ktor.client.request.get
//import io.ktor.client.statement.HttpStatement
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json


private const val LOG_TAG = "MainScreenViewModel"

@Serializable data class Message(val role: String, val content: String)

@Serializable data class OpenAIData(val model: String, val messages: List<Message>)

class MainScreenViewModel(private val application: Application) : ViewModel() {
    var canTranscribe by mutableStateOf(false)
        private set
    var dataLog by mutableStateOf("")
        private set
    var isRecording by mutableStateOf(false)
        private set

    private val modelsPath = File(application.filesDir, "models")
    private val samplesPath = File(application.filesDir, "samples")
    private var recorder: Recorder = Recorder()
    private var whisperContext: WhisperContext? = null
    private var mediaPlayer: MediaPlayer? = null
    private var recordedFile: File? = null
    val client = HttpClient(Android) {
//        serializer = KotlinxSerializer(Json {
        install(ContentNegotiation) {
            json(Json {})
        }
//            prettyPrint = true
//            isLenient = true
//        })
    }
    val OPENAI_API_KEY ="OPENAI_API_KEY"
//    val hist = ListOf(Message)
    init {
        viewModelScope.launch {
            printSystemInfo()
            loadData()
        }
    }

    private suspend fun printSystemInfo() {
        printMessage(String.format("System Info: %s\n", WhisperContext.getSystemInfo()));
    }

    private suspend fun loadData() {
        printMessage("Loading data...\n")
        try {
            copyAssets()
            loadBaseModel()
            canTranscribe = true
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }
    }

    private suspend fun printMessage(msg: String) = withContext(Dispatchers.Main) {
        dataLog += msg
    }

    private suspend fun copyAssets() = withContext(Dispatchers.IO) {
        modelsPath.mkdirs()
        samplesPath.mkdirs()
        //application.copyData("models", modelsPath, ::printMessage)
        application.copyData("samples", samplesPath, ::printMessage)
        printMessage("All data copied to working directory.\n")
    }

    private suspend fun loadBaseModel() = withContext(Dispatchers.IO) {
        printMessage("Loading model...\n")
        val models = application.assets.list("models/")
        models?.let {
            for (model in it) {
                printMessage(model)
                printMessage("\n")

            }
        } ?: run {
            println("Error: Could not list models.")
        }

        if (models != null) {
            whisperContext =
                WhisperContext.createContextFromAsset(application.assets, "models/" + models[2])
            printMessage("Loaded model ${models[2]}.\n")
        }

        //val firstModel = modelsPath.listFiles()!!.first()
        //whisperContext = WhisperContext.createContextFromFile(firstModel.absolutePath)
    }

    fun benchmark() = viewModelScope.launch {
        runBenchmark(6)
    }

    fun transcribeSample() = viewModelScope.launch {
        transcribeAudio(getFirstSample())
    }

    private suspend fun runBenchmark(nthreads: Int) {
        if (!canTranscribe) {
            return
        }

        canTranscribe = false

        printMessage("Running benchmark. This will take minutes...\n")
        whisperContext?.benchMemory(nthreads)?.let { printMessage(it) }
        printMessage("\n")
        whisperContext?.benchGgmlMulMat(nthreads)?.let { printMessage(it) }

        canTranscribe = true
    }

    private suspend fun getFirstSample(): File = withContext(Dispatchers.IO) {
        samplesPath.listFiles()!!.first()
    }

    private suspend fun readAudioSamples(file: File): FloatArray = withContext(Dispatchers.IO) {
        stopPlayback()
        startPlayback(file)
        return@withContext decodeWaveFile(file)
    }

    private suspend fun stopPlayback() = withContext(Dispatchers.Main) {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private suspend fun startPlayback(file: File) = withContext(Dispatchers.Main) {
        mediaPlayer = MediaPlayer.create(application, file.absolutePath.toUri())
        mediaPlayer?.start()
    }

    private suspend fun transcribeAudio(file: File) {
        if (!canTranscribe) {
            return
        }

        canTranscribe = false

        try {
            printMessage("Reading wave samples... ")
            val data = readAudioSamples(file)
            printMessage("${data.size / (16000 / 1000)} ms\n")
            printMessage("Transcribing data...\n")
            val start = System.currentTimeMillis()
            val text = whisperContext?.transcribeData(data)
            val elapsed = System.currentTimeMillis() - start

            val msg = "Done ($elapsed ms): $text\n"
            printMessage(msg)

            val to_send = OpenAIData("gpt-3.5-turbo", listOf(Message("user", text!!)))
            val jsonString = Json.encodeToString(to_send)

            val response: HttpResponse = client.post("https://api.openai.com/v1/chat/completions") {

                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $OPENAI_API_KEY")
                setBody(jsonString)
            }
            val jsonObject = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val choices = jsonObject["choices"] as JsonArray
            val firstChoice = choices[0].jsonObject
            val message = firstChoice["message"] as JsonObject
            val content = message["content"]!!.jsonPrimitive.content

            println(content)
            printMessage(content)

        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
        }

        canTranscribe = true
    }


    fun toggleRecord() = viewModelScope.launch {
        try {
            if (isRecording) {
                recorder.stopRecording()
                isRecording = false
                recordedFile?.let { transcribeAudio(it) }
            } else {
                stopPlayback()
                val file = getTempFileForRecording()
                recorder.startRecording(file) { e ->
                    viewModelScope.launch {
                        withContext(Dispatchers.Main) {
                            printMessage("${e.localizedMessage}\n")
                            isRecording = false
                        }
                    }
                }
                isRecording = true
                recordedFile = file
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, e)
            printMessage("${e.localizedMessage}\n")
            isRecording = false
        }
    }

    suspend fun loadSelectedModel(modelName: String) {
        printMessage("Loading model $modelName...\n")
        whisperContext =
            WhisperContext.createContextFromAsset(application.assets, "models/$modelName")
        printMessage("Loaded model $modelName.\n")
        canTranscribe = true
    }


    private suspend fun getTempFileForRecording() = withContext(Dispatchers.IO) {
        File.createTempFile("recording", "wav")
    }

    override fun onCleared() {
        runBlocking {
            whisperContext?.release()
            whisperContext = null
            stopPlayback()
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                MainScreenViewModel(application)
            }
        }
    }
}

private suspend fun Context.copyData(
    assetDirName: String,
    destDir: File,
    printMessage: suspend (String) -> Unit
) = withContext(Dispatchers.IO) {
    assets.list(assetDirName)?.forEach { name ->
        val assetPath = "$assetDirName/$name"
        Log.v(LOG_TAG, "Processing $assetPath...")
        val destination = File(destDir, name)
        Log.v(LOG_TAG, "Copying $assetPath to $destination...")
        printMessage("Copying $name...\n")
        assets.open(assetPath).use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Log.v(LOG_TAG, "Copied $assetPath to $destination")
    }
}

