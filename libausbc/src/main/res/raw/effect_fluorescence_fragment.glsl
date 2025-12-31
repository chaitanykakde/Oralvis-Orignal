precision mediump float;
uniform sampler2D uTextureSampler;
varying vec2 vTextureCoord;

void main()
{
    // Optimized fluorescence effect: boost blue channel, reduce red/green
    // Using direct channel access and optimized calculations for better performance
    vec4 tempColor = texture2D(uTextureSampler, vTextureCoord);
    
    // Optimized: calculate channels directly without intermediate variables
    // Using lowp precision for color calculations (acceptable quality, better performance)
    lowp float r = tempColor.r * 0.3;  // Reduce red
    lowp float g = tempColor.g * 0.4;  // Reduce green
    lowp float b = tempColor.b * 1.3;  // Boost blue (clamp handled by GPU)
    
    // Clamp blue to 1.0 and output final color
    gl_FragColor = vec4(r, g, min(b, 1.0), tempColor.a);
}

