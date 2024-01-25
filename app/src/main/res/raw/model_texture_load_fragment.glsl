precision mediump float;
varying vec2 TexCoord;
varying vec3 fragPos;
varying vec3 norm;

// 定义材质结构体
struct Material {
    sampler2D ambient;
    sampler2D diffuse;
    sampler2D specular;

    float shininess;
    float alpha;
};
uniform Material material;

// 定义光源结构体
struct Light {
    vec3 position;

    vec3 ambient;
    vec3 diffuse;
    vec3 specular;
};

uniform Light light;
void main() {
    // 环境光照
    vec3 ambient = light.ambient * texture2D(material.ambient, TexCoord).rgb;

    // 漫反射光照
    // 归一化光源线
    vec3 lightDir = normalize(light.position - fragPos);
    float diff = max(dot(norm, lightDir), 0.0);
    vec3 diffuse = diff * light.diffuse * texture2D(material.diffuse, TexCoord).rgb;

    // 镜面光照
    vec3 viewDir = normalize(-fragPos);
    vec3 reflectDir = reflect(-lightDir, norm);
    float spec = pow(max(dot(viewDir, reflectDir), 0.0), material.shininess);
    vec3 specular = (spec * light.specular) * texture2D(material.specular, TexCoord).rgb;
    // 结果
    vec3 result = (ambient + diffuse + specular);

    gl_FragColor = vec4(result, material.alpha);
}