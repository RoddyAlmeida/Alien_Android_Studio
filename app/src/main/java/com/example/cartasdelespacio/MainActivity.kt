package com.example.cartasdelespacio

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.sceneview.SceneView
import io.github.sceneview.node.ModelNode
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.math.Scale
import io.github.sceneview.math.Direction
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random
import kotlin.math.pow
import kotlin.math.sqrt
import android.animation.ValueAnimator
import com.google.ar.core.ArCoreApk
import org.json.JSONArray
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// Clase de datos para asociar un ModelNode con su ID de OVNI
data class UfoData(
    val node: ModelNode,
    val ufoId: String
)

// Clase para guardar el estado de la historia y decisiones del jugador
data class StoryState(
    var capitulo: Int = 0,
    var ayudasteZhur: Boolean = false,
    var piezasEncontradas: Int = 0,
    var zhurConfiado: Boolean = false
)

private val MAX_CAPITULOS = 3

class MainActivity : AppCompatActivity() {
    private lateinit var sceneView: SceneView
    private lateinit var dialogueText: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var explorationHint: TextView
    private lateinit var dialogueTree: JSONObject
    private var currentNode: String = "inicio"
    private var isDialogueActive = false
    private var ufoNode: ModelNode? = null
    private var ufoAnimator: ValueAnimator? = null
    private var ufoPulseAnimator: ValueAnimator? = null
    private var storyState = StoryState()
    private var nextSerieNode: String? = null
    private var nextBlockNode: String? = null
    private var waitingForNextBlock = false
    private val CAMERA_PERMISSION_REQUEST = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_main)

            // Inicializar vistas
            sceneView = findViewById(R.id.sceneView)
            dialogueText = findViewById(R.id.dialogueText)
            optionsContainer = findViewById(R.id.optionsContainer)
            explorationHint = findViewById(R.id.explorationHint)

            // Verificar permisos de cámara
            if (!checkCameraPermissions()) {
                requestCameraPermissions()
                return
            }

            // Validar compatibilidad con ARCore
            if (!isArCoreSupported()) {
                dialogueText.text = "Este dispositivo no es compatible con Realidad Aumentada (ARCore)."
                sceneView.visibility = View.GONE
                explorationHint.visibility = View.GONE
                return
            }

            // Verificar si ARCore está funcionando
            if (!isArCoreWorking()) {
                dialogueText.text = "Error al inicializar ARCore. Verifica que tengas permisos de cámara."
                sceneView.visibility = View.GONE
                explorationHint.visibility = View.GONE
                return
            }

            // Configurar SceneView para detección de superficies y toques
            setupSceneView()
            
            // Cargar el árbol de diálogo
            loadDialogueTree()
            
            // Coloca el OVNI solo una vez
            sceneView.postDelayed({
                placeUfoInEnvironment()
            }, 2000)
        } catch (e: Exception) {
            e.printStackTrace()
            dialogueText.text = "Error al inicializar la aplicación. Por favor, reinicia la app."
        }
    }

    private fun checkCameraPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST)
        dialogueText.text = "Esta app necesita permisos de cámara para funcionar. Por favor, otorga los permisos."
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // Permisos otorgados, reiniciar la app
                    recreate()
                } else {
                    dialogueText.text = "Sin permisos de cámara, la app no puede funcionar. Por favor, otorga los permisos en Configuración."
                }
            }
        }
    }

    private fun isArCoreSupported(): Boolean {
        return try {
            val availability = ArCoreApk.getInstance().checkAvailability(this)
            availability.isSupported
        } catch (e: Exception) {
            Log.e("ARCore", "Error checking ARCore availability", e)
            false
        }
    }

    private fun isArCoreWorking(): Boolean {
        return try {
            val session = ArCoreApk.getInstance().requestInstall(this, true)
            session == ArCoreApk.InstallStatus.INSTALLED
        } catch (e: Exception) {
            Log.e("ARCore", "Error checking ARCore installation", e)
            false
        }
    }

    private fun setupSceneView() {
        try {
            sceneView.setOnTouchListener { v, event ->
                try {
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        handleTouch(event.x, event.y)
                    }
                    // Deja que SceneView procese el evento también
                    v.onTouchEvent(event)
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun loadDialogueTree() {
        try {
            val inputStream = assets.open("dialogo.json")
            val reader = BufferedReader(InputStreamReader(inputStream))
            val jsonString = reader.readText()
            dialogueTree = JSONObject(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun placeUfoInEnvironment() {
        explorationHint.visibility = View.GONE
        if (ufoNode == null) {
            val node = createUfoNode()
            val randomX = -0.5f + (0.5f - (-0.5f)) * kotlin.random.Random.nextFloat()
            val randomZ = -1.8f + (-1.2f - (-1.8f)) * kotlin.random.Random.nextFloat()
            node.position = Position(randomX, 0.2f, randomZ)
            node.rotation = Rotation(0f, 0f, 0f)
            sceneView.addChild(node)
            ufoNode = node
            animateUfoSmoothly(node)
            startUfoPulse()
        }
        // Al inicio, muestra el primer diálogo
        currentNode = "bloque1_nodo1"
        isDialogueActive = false
        waitingForNextBlock = false
        dialogueText.text = "Toca el OVNI para comenzar la historia..."
        optionsContainer.removeAllViews()
    }

    private fun createUfoNode(): ModelNode {
        return ModelNode(
            engine = sceneView.engine,
            modelGlbFileLocation = "models/cube.glb",
            scaleUnits = 0.3f,
            centerOrigin = null
        )
    }

    private fun animateUfoSmoothly(ufoNode: ModelNode) {
        ufoAnimator?.cancel()
        val originalY = ufoNode.position.y
        ufoAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                val value = animation.animatedValue as Float
                ufoNode.position = Position(ufoNode.position.x, originalY + 0.1f * value, ufoNode.position.z)
            }
            start()
        }
    }

    private fun startUfoPulse() {
        ufoPulseAnimator?.cancel()
        ufoNode?.let { node ->
            val originalScale = node.scale.x
            ufoPulseAnimator = ValueAnimator.ofFloat(1f, 1.15f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { animation ->
                    val value = animation.animatedValue as Float
                    node.scale = Scale(originalScale * value)
                }
                start()
            }
        }
    }

    private fun stopUfoPulse() {
        ufoPulseAnimator?.cancel()
        ufoPulseAnimator = null
        ufoNode?.scale = Scale(0.3f)
    }

    private fun handleTouch(x: Float, y: Float) {
        try {
            Log.d("OVNI_TOUCH", "isDialogueActive=$isDialogueActive, waitingForNextBlock=$waitingForNextBlock, nextBlockNode=$nextBlockNode")
            if (isDialogueActive && !waitingForNextBlock) return
            // Solo permitir interacción si el OVNI está presente
            if (ufoNode != null) {
                if (waitingForNextBlock && nextBlockNode != null) {
                    Log.d("OVNI_TOUCH", "Avanzando a siguiente bloque: $nextBlockNode")
                    // Iniciar siguiente bloque
                    currentNode = nextBlockNode!!
                    isDialogueActive = true
                    waitingForNextBlock = false
                    updateDialogue()
                } else if (!waitingForNextBlock) {
                    Log.d("OVNI_TOUCH", "Iniciando diálogo normal")
                    startDialogue()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startDialogue() {
        try {
            isDialogueActive = true
            ufoNode?.scale = Scale(0.4f)
            updateDialogue()
        } catch (e: Exception) {
            e.printStackTrace()
            isDialogueActive = false
        }
    }

    private fun updateDialogue() {
        try {
            val node = dialogueTree.getJSONObject(currentNode)
            val texto = node.getString("texto")
            val opciones = node.optJSONArray("opciones") ?: JSONArray()
            dialogueText.text = texto
            optionsContainer.removeAllViews()
            val finalMalo = node.optBoolean("finalMalo", false)
            val siguienteBloque = node.optString("siguienteBloque", null)
            nextBlockNode = if (siguienteBloque.isNullOrEmpty()) null else siguienteBloque
            Log.d("OVNI_DIALOG", "updateDialogue: currentNode=$currentNode, finalMalo=$finalMalo, nextBlockNode=$nextBlockNode")
            if (opciones.length() == 0) {
                // Nodo final
                if (finalMalo) {
                    isDialogueActive = true
                    // OVNI desaparece para siempre
                    ufoNode?.let { sceneView.removeChild(it) }
                    ufoNode = null
                    val restartButton = Button(this)
                    restartButton.text = "Reiniciar historia"
                    restartButton.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 8, 0, 8) }
                    restartButton.setOnClickListener { restartStory() }
                    optionsContainer.addView(restartButton)
                    waitingForNextBlock = false
                } else if (nextBlockNode != null) {
                    // OVNI se reubica y espera a que el usuario lo toque para continuar
                    moveUfoToNewPosition()
                    dialogueText.text = "Explora y vuelve a tocar el OVNI para continuar la historia..."
                    waitingForNextBlock = true
                    isDialogueActive = false
                } else {
                    // Final bueno sin siguiente bloque
                    isDialogueActive = true
                    val restartButton = Button(this)
                    restartButton.text = "Reiniciar historia"
                    restartButton.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 8, 0, 8) }
                    restartButton.setOnClickListener { restartStory() }
                    optionsContainer.addView(restartButton)
                    waitingForNextBlock = false
                }
            } else {
                for (i in 0 until opciones.length()) {
                    val opcion = opciones.getJSONObject(i)
                    val textoOpcion = opcion.getString("texto")
                    val siguiente = opcion.getString("siguiente")
                    val button = Button(this)
                    button.text = textoOpcion
                    button.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 8, 0, 8) }
                    button.setOnClickListener { handleOptionClick(siguiente) }
                    optionsContainer.addView(button)
                }
                waitingForNextBlock = false
                isDialogueActive = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            closeDialogue()
        }
    }

    private fun handleOptionClick(siguiente: String) {
        currentNode = siguiente
        // Si el siguiente nodo es final, desactiva interacción
        updateDialogue()
        if (isFinalNode(currentNode)) {
            isDialogueActive = true
        } else {
            isDialogueActive = false
            moveUfoToNewPosition()
        }
    }

    private fun showEndingMessage(finalNode: String) {
        val mensaje = when (finalNode) {
            "finalAyudado" -> "¡Lo ayudaste! Zhur está agradecido."
            "finalIgnorado" -> "Zhur se va y su nave desaparece."
            else -> "Fin de la conversación."
        }
        dialogueText.text = mensaje
        optionsContainer.removeAllViews()
        val restartButton = Button(this)
        restartButton.text = "Continuar"
        restartButton.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 8)
        }
        restartButton.setOnClickListener {
            closeDialogue()
        }
        optionsContainer.addView(restartButton)
    }

    private fun closeDialogue() {
        try {
            isDialogueActive = false
            ufoNode?.scale = Scale(0.3f)
            dialogueText.text = "Toca el OVNI para continuar la historia..."
            optionsContainer.removeAllViews()
            explorationHint.visibility = View.VISIBLE
        } catch (e: Exception) {
            e.printStackTrace()
            isDialogueActive = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ufoAnimator?.cancel()
        ufoPulseAnimator?.cancel()
    }

    // Reinicia la historia
    private fun restartStory() {
        storyState = StoryState()
        currentNode = "bloque1_nodo1"
        isDialogueActive = false
        waitingForNextBlock = false
        if (ufoNode == null) {
            placeUfoInEnvironment()
        } else {
            updateDialogue()
        }
    }

    // Utilidad para saber si un nodo es final
    private fun isFinalNode(nodeKey: String): Boolean {
        return try {
            val node = dialogueTree.getJSONObject(nodeKey)
            val opciones = node.getJSONArray("opciones")
            opciones.length() == 0
        } catch (e: Exception) {
            true
        }
    }

    private fun moveUfoToNewPosition() {
        ufoNode?.let { node ->
            val randomX = -0.5f + (0.5f - (-0.5f)) * kotlin.random.Random.nextFloat()
            val randomZ = -1.8f + (-1.2f - (-1.8f)) * kotlin.random.Random.nextFloat()
            node.position = Position(randomX, 0.2f, randomZ)
        }
    }
}