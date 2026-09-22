package com.library.lms.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.library.lms.dto.ChatRequest;
import com.library.lms.dto.ChatResponse;
import com.library.lms.service.ChatService;

import jakarta.validation.Valid;

/**
 * Asking the assistant a question.
 *
 * <p>Open to every signed-in account - members, librarians and administrators -
 * and to nobody else. The caller's library is taken from their account by
 * {@link ChatService}, never from the request, so an answer is always about
 * their own library.</p>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * POST /api/chat - answers one question.
     *
     * <p>Answers 200 with the reply. A missing, blank or over-long message is a
     * 400 from the usual validation handler, and an anonymous caller gets a 401
     * from the filter chain without the assistant being reached at all.</p>
     */
    @PostMapping
    public ResponseEntity<ChatResponse> reply(@Valid @RequestBody ChatRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(chatService.reply(request.getMessage(), authentication.getName()));
    }
}
