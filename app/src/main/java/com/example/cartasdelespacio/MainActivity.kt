package com.example.cartasdelespacio

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.rendering.ModelRenderable
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Renderable
import com.google.ar.sceneform.ux.TransformableNode
import com.google.ar.core.Anchor
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
    val node: TransformableNode,
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
    private lateinit var arFragment: ArFragment
    private lateinit var dialogueText: TextView
    private lateinit var optionsContainer: LinearLayout
    private lateinit var explorationHint: TextView
    private lateinit var dialogueTree: JSONObject
    private var currentNode: String = "inicio"
    private var isDialogueActive = false
    private var ufoNode: TransformableNode? = null
    private var ufoAnchorNode: AnchorNode? = null
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
            arFragment = supportFragmentManager.findFragmentById(R.id.arFragment) as ArFragment
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
                arFragment.arSceneView.visibility = View.GONE
                explorationHint.visibility = View.GONE
                return
            }

            // Cargar el árbol de diálogo
            loadDialogueTree()

            // Listener para colocar el OVNI al tocar un plano
            arFragment.setOnTapArPlaneListener { hitResult, plane, motionEvent ->
                if (ufoNode == null && !waitingForNextBlock) {
                    placeUfoInEnvironment(hitResult.createAnchor())
                } else if (waitingForNextBlock && nextBlockNode != null) {
                    // Elimina el OVNI anterior y coloca uno nuevo para el siguiente bloque
                    removeUfoFromScene()
                    placeUfoInEnvironment(hitResult.createAnchor())
                    currentNode = nextBlockNode!!
                    isDialogueActive = false
                    waitingForNextBlock = false
                    updateDialogue()
                }
            }
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

    private fun placeUfoInEnvironment(anchor: Anchor) {
        explorationHint.visibility = View.GONE
        ModelRenderable.builder()
            .setSource(this, android.net.Uri.parse("models/cube.glb"))
            .build()
            .thenAccept { renderable ->
                addUfoToScene(anchor, renderable)
            }
            .exceptionally { throwable ->
                dialogueText.text = "Error al cargar el modelo 3D."
                null
            }
    }

    private fun addUfoToScene(anchor: Anchor, renderable: Renderable) {
        val anchorNode = AnchorNode(anchor)
        anchorNode.setParent(arFragment.arSceneView.scene)
        val node = TransformableNode(arFragment.transformationSystem)
        node.renderable = renderable
        node.setParent(anchorNode)
        node.select()
        ufoNode = node
        ufoAnchorNode = anchorNode
        // Listener para interacción con el OVNI
        node.setOnTapListener { _, _ ->
            if (!isDialogueActive) startDialogue()
            else if (waitingForNextBlock && nextBlockNode != null) {
                // Permite avanzar al siguiente bloque tocando el OVNI si ya está colocado
                removeUfoFromScene()
                // Espera a que el usuario toque un nuevo plano para colocar el OVNI
                dialogueText.text = "Toca una superficie para continuar la historia..."
            }
        }
        // Mensaje inicial
        if (!waitingForNextBlock) {
            currentNode = "bloque1_nodo1"
            isDialogueActive = false
            waitingForNextBlock = false
            dialogueText.text = "Toca el OVNI para comenzar la historia..."
            optionsContainer.removeAllViews()
        }
    }

    private fun removeUfoFromScene() {
        ufoNode?.setParent(null)
        ufoAnchorNode?.setParent(null)
        ufoNode = null
        ufoAnchorNode = null
    }

    private fun startDialogue() {
        try {
            isDialogueActive = true
            ufoNode?.localScale = Vector3(0.4f, 0.4f, 0.4f)
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
                    removeUfoFromScene()
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
                    removeUfoFromScene()
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
            removeUfoFromScene() // Elimina el OVNI actual si no es final
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
            ufoNode?.localScale = Vector3(0.3f, 0.3f, 0.3f)
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
        // No hay animadores para cancelar, ya que no se usan ModelNode
    }

    // Reinicia la historia
    private fun restartStory() {
        storyState = StoryState()
        currentNode = "bloque1_nodo1"
        isDialogueActive = false
        waitingForNextBlock = false
        if (ufoNode == null) {
            // No hay OVNI para colocar, solo reiniciar el estado
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
            node.localPosition = Vector3(randomX, 0.2f, randomZ)
        }
    }
}