package de.smartmeter.blink

/** Which physical light is used to talk to the meter's optical sensor. */
enum class LightSourceMode { TORCH, SCREEN }

/**
 * Abstraction over the two light sources the meter understands:
 * the camera torch, or a full-white screen flash.
 */
interface LightSource
{
    fun turnOn(): Boolean
    fun turnOff()
}

/** Torch backed light source. */
class TorchLightSource(private val controller: FlashlightController) : LightSource
{
    override fun turnOn(): Boolean = controller.setTorch(true)
    override fun turnOff()
    {
        controller.setTorch(false)
    }
}

/** Screen strobe source; `on` flips a flag that the UI renders as full-white. */
class ScreenLightSource(private val setStrobe: (Boolean) -> Unit) : LightSource
{
    override fun turnOn(): Boolean
    {
        setStrobe(true)
        return true
    }

    override fun turnOff()
    {
        setStrobe(false)
    }
}