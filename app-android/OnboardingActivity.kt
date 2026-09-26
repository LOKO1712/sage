package com.tuapp.hogarseguro

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Onboarding SIMPLE A PROPOSITO: la lista de pantallas se arma UNA SOLA VEZ
 * en onCreate y nunca se recalcula durante el recorrido. Cada boton hace su
 * accion (abrir un ajuste, pedir un permiso) y SIEMPRE avanza de inmediato,
 * sin esperar ni verificar nada de Android. Cero logica condicional de
 * "ya se resolvio o no" mientras el usuario esta recorriendo las pantallas -
 * eso es lo que causaba todos los bugs anteriores.
 *
 * La verificacion REAL de si los permisos quedaron bien configurados pasa a
 * SplashActivity, que decide en cada apertura de la app si hay que volver a
 * mandar al usuario aca.
 */
class OnboardingActivity : AppCompatActivity() {

    private data class Pantalla(
        val imagenResId: Int,
        val titulo: String,
        val descripcion: String,
        val textoBoton: String,
        val mostrarAhoraNo: Boolean = false,
        val accionBoton: () -> Unit
    )

    private var indiceActual = 0
    private lateinit var pantallas: List<Pantalla>
    private var procesandoClick = false

    private lateinit var imgFondo: ImageView
    private lateinit var txtProgreso: TextView
    private lateinit var txtTitulo: TextView
    private lateinit var txtDescripcion: TextView
    private lateinit var btnActivar: Button
    private lateinit var txtAhoraNo: TextView
    private lateinit var viewFade: android.view.View
    private lateinit var contenedorTexto: android.widget.LinearLayout

    // Se registra aca (obligatorio hacerlo en la inicializacion de la
    // Activity, Android no permite registrarlo dentro de un click). No nos
    // importa el resultado - el flujo avanza igual, sin esperarlo.
    private val solicitarPostNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        imgFondo = findViewById(R.id.imgFondo)
        txtProgreso = findViewById(R.id.txtProgreso)
        txtTitulo = findViewById(R.id.txtTitulo)
        txtDescripcion = findViewById(R.id.txtDescripcion)
        btnActivar = findViewById(R.id.btnActivar)
        txtAhoraNo = findViewById(R.id.txtAhoraNo)
        viewFade = findViewById(R.id.viewFade)
        contenedorTexto = findViewById(R.id.contenedorTexto)

        // Se arma UNA sola vez aca. NUNCA se vuelve a calcular durante el
        // recorrido (ni en onResume, ni en ningun otro lado).
        pantallas = construirPantallas()
        indiceActual = 0
        mostrarPantallaActual()
    }

    private fun construirPantallas(): List<Pantalla> {
        val lista = mutableListOf<Pantalla>()

        // Bienvenida - solo la primera vez que se instala la app
        if (!PermisosSage.introCompletada(this)) {
            lista += Pantalla(
                imagenResId = R.drawable.intro_bienvenido,
                titulo = "Bienvenido a SAGE",
                descripcion = "",
                textoBoton = "Continuar",
                accionBoton = { avanzarConAnimacion() }
            )
            lista += Pantalla(
                imagenResId = R.drawable.intro_cuida,
                titulo = "SAGE no solo cuida tu casa, te cuida a ti",
                descripcion = "Somos una red que monitorea tu hogar y te mantiene " +
                        "informado con alertas en tiempo real, para que estés " +
                        "siempre un paso adelante.",
                textoBoton = "Continuar",
                accionBoton = {
                    PermisosSage.marcarIntroCompletada(this)
                    avanzarConAnimacion()
                }
            )
        }

        // Notificaciones - siempre se muestra, sin condicion
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lista += Pantalla(
                imagenResId = R.drawable.ilustracion_notificaciones,
                titulo = "Activa las notificaciones",
                descripcion = "Recibe alertas en tiempo real sobre fugas de gas, " +
                        "fallas eléctricas o sismos, y mantén tu hogar más seguro.",
                textoBoton = "Activar notificaciones",
                mostrarAhoraNo = true,
                accionBoton = {
                    solicitarPostNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    avanzarPagina()
                }
            )
        }

        // Acceso a notificaciones - siempre se muestra, sin condicion
        lista += Pantalla(
            imagenResId = R.drawable.ilustracion_acceso_notificaciones,
            titulo = "Permite el acceso a tus notificaciones",
            descripcion = "Esta app trabaja junto con servicios como las alertas " +
                    "de sismos de Google y otras fuentes oficiales para detectar " +
                    "notificaciones de estaciones sísmicas y brindarte una " +
                    "alerta temprana.",
            textoBoton = "Abrir ajuste",
            mostrarAhoraNo = true,
            accionBoton = {
                abrirAjusteConFade {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
            }
        )

        // Bateria - siempre se muestra, sin condicion
        lista += Pantalla(
            imagenResId = R.drawable.ilustracion_bateria,
            titulo = "Desactiva las restricciones de batería",
            descripcion = "Android puede cerrar la aplicación para ahorrar " +
                    "batería y esto impediría recibir notificaciones del estado " +
                    "de tu vivienda y algunas alertas sísmicas.",
            textoBoton = "Desactivar restricción",
            mostrarAhoraNo = true,
            accionBoton = {
                abrirAjusteConFade {
                    try {
                        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        })
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ve a Ajustes -> Batería y desactívala a mano", Toast.LENGTH_LONG).show()
                    }
                    PermisosSage.marcarBateriaConfirmada(this)
                }
            }
        )

        // Autoinicio - solo en Xiaomi/POCO, siempre se muestra ahi
        if (PermisosSage.esXiaomi()) {
            lista += Pantalla(
                imagenResId = R.drawable.ilustracion_autoinicio,
                titulo = "Activa el inicio automático",
                descripcion = "Configura el arranque de esta aplicación al encender " +
                        "tu teléfono. Así, cuando reinicies tu dispositivo, la app " +
                        "seguirá funcionando, escuchando alertas y cuidándote.",
                textoBoton = "Abrir ajuste",
                mostrarAhoraNo = true,
                accionBoton = {
                    abrirAjusteConFade {
                        try {
                            startActivity(Intent().apply {
                                component = ComponentName(
                                    "com.miui.securitycenter",
                                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                                )
                            })
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(
                                this,
                                "Ve a Ajustes -> Apps -> Permisos -> Autoinicio, y actívala a mano",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        PermisosSage.marcarAutoInicioConfirmado(this)
                    }
                }
            )
        }

        // Cierre - siempre la ultima
        lista += Pantalla(
            imagenResId = R.drawable.intro_listo,
            titulo = "SAGE está listo, tu hogar también",
            descripcion = "Tu vivienda ahora es parte de SAGE. La red que protege tu hogar.",
            textoBoton = "Comenzar",
            accionBoton = { finalizarOnboarding() }
        )

        return lista
    }

    private fun mostrarPantallaActual() {
        if (indiceActual < 0 || indiceActual >= pantallas.size) return
        val p = pantallas[indiceActual]
        imgFondo.setImageResource(p.imagenResId)
        txtProgreso.text = "Paso ${indiceActual + 1} de ${pantallas.size}"
        txtTitulo.text = p.titulo
        txtDescripcion.text = p.descripcion
        txtDescripcion.visibility = if (p.descripcion.isBlank()) android.view.View.GONE else android.view.View.VISIBLE
        btnActivar.text = p.textoBoton
        btnActivar.setOnClickListener { manejarClick(p.accionBoton) }

        if (p.mostrarAhoraNo) {
            txtAhoraNo.visibility = android.view.View.VISIBLE
            txtAhoraNo.setOnClickListener { manejarClick { avanzarPagina() } }
        } else {
            txtAhoraNo.visibility = android.view.View.INVISIBLE
        }
    }

    /**
     * Transicion suave (crossfade) entre pantallas, para los botones
     * "Continuar" - NO para las de permisos, esas usan el fade a negro.
     * Dura 2 segundos en total (1s desvanece, 1s aparece).
     */
    private fun avanzarConAnimacion() {
        imgFondo.animate().alpha(0f).setDuration(1000).start()
        contenedorTexto.animate().alpha(0f).setDuration(1000).withEndAction {
            avanzarPagina()
            imgFondo.alpha = 0f
            contenedorTexto.alpha = 0f
            imgFondo.animate().alpha(1f).setDuration(1000).start()
            contenedorTexto.animate().alpha(1f).setDuration(1000).start()
        }.start()
    }

    private fun manejarClick(accion: () -> Unit) {
        if (procesandoClick) return
        procesandoClick = true
        accion()
        btnActivar.postDelayed({ procesandoClick = false }, 400)
    }

    /**
     * Hace un fade lento a negro (1s), y AL TERMINAR el fade recien ahi
     * ejecuta la accion que abre Ajustes y avanza la pantalla por debajo
     * (oculta detras del negro). Cuando el usuario vuelve de Ajustes,
     * onResume() se encarga de desvanecer el negro de nuevo (otro 1s) -
     * en total, unos 2 segundos de transicion, como pediste.
     */
    private fun abrirAjusteConFade(accionAjuste: () -> Unit) {
        viewFade.animate()
            .alpha(1f)
            .setDuration(1000)
            .withEndAction {
                accionAjuste()
                avanzarPagina()
            }
            .start()
    }

    override fun onResume() {
        super.onResume()
        // Si el overlay quedo negro (porque acabamos de volver de Ajustes),
        // lo desvanecemos de nuevo para revelar la siguiente pantalla.
        if (viewFade.alpha > 0f) {
            viewFade.animate().alpha(0f).setDuration(1000).start()
        }
    }

    /** SIEMPRE avanza, sin condiciones, sin esperar nada. */
    private fun avanzarPagina() {
        indiceActual++
        mostrarPantallaActual()
    }

    private fun finalizarOnboarding() {
        PermisosSage.marcarIntroCompletada(this)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}