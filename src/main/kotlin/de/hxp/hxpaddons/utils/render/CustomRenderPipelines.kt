package de.hxp.hxpaddons.utils.render

import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import java.util.*

object CustomRenderPipelines {
    val LINES_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withDepthStencilState(Optional.empty())
            .withLocation("hxpaddons/lines_esp")
            .build()
    )

    val LINES_TRANSLUCENT_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
            .withDepthStencilState(Optional.empty())
            .withLocation("hxpaddons/lines_translucent_esp")
            .build()
    )

    val QUADS_ESP: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withDepthStencilState(Optional.empty())
            .withLocation("hxpaddons/quads_esp")
            .build()
    )

    val PIPELINE_ROUND_RECT: RenderPipeline = RenderPipelines.register(
        RenderPipeline.builder(RenderPipelines.GUI_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("hxpaddons", "pipeline/round_rect"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("hxpaddons", "core/round_rect"))
            .withVertexShader(Identifier.fromNamespaceAndPath("hxpaddons", "core/round_rect"))
            .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
            .withUniform("u", UniformType.UNIFORM_BUFFER)
            .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT))
            .build()
    )
}
