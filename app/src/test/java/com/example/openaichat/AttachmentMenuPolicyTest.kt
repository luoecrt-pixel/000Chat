package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentMenuPolicyTest {
    @Test fun plusMenuOffersOnlyImplementedAttachmentActionsInUserOrder() {
        assertEquals(
            listOf(AttachmentMenuAction.CAMERA, AttachmentMenuAction.PHOTO, AttachmentMenuAction.FILES),
            AttachmentMenuPolicy.actions
        )
        assertEquals(listOf("Камера", "Фото", "Файлы"), AttachmentMenuPolicy.actions.map { it.title })
        assertTrue(AttachmentMenuPolicy.actions.map { it.title }.distinct().size == 3)
    }
}
