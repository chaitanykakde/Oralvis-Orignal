precision mediump float;
uniform sampler2D uTextureSampler;
varying vec2 vTextureCoord;

void main()
{
    // Fluorescence effect: very soft blue tint.
    // The hardware already sends a fluorescence-colored preview, so we only apply
    // a light blue emphasis instead of a strong artificial blue overlay.
    vec4 tempColor = texture2D(uTextureSampler, vTextureCoord);
    
    // Apply a medium adjustment:
    // - keep most of the original red/green
    // - moderately boost blue for a clearer fluorescence feel
    lowp float r = tempColor.r * 0.8;   // Small red reduction
    lowp float g = tempColor.g * 0.85;  // Small green reduction
    lowp float b = tempColor.b * 1.15;  // Moderate blue boost
    
    // Clamp blue to 1.0 and output final color
    gl_FragColor = vec4(r, g, min(b, 1.0), tempColor.a);
}

