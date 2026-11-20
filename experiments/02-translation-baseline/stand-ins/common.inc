#define texture2D texture
#define texture2DLod textureLod
#define texture3D texture
#define shadow2D(s, c) vec4(texture(s, c))
#define gl_TextureMatrix ms_TextureMatrix
#define gl_ModelViewMatrix ms_ModelViewMatrix
#define gl_ProjectionMatrix ms_ProjectionMatrix
#define gl_ModelViewProjectionMatrix ms_ModelViewProjectionMatrix
#define gl_NormalMatrix ms_NormalMatrix
uniform mat4 ms_TextureMatrix[8];
uniform mat4 ms_ModelViewMatrix;
uniform mat4 ms_ProjectionMatrix;
uniform mat4 ms_ModelViewProjectionMatrix;
uniform mat3 ms_NormalMatrix;
struct ms_FogParameters { vec4 color; float density; float start; float end; float scale; };
uniform ms_FogParameters ms_Fog;
#define gl_Fog ms_Fog
