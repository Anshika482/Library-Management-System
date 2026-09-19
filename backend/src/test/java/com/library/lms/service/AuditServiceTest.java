package com.library.lms.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.library.lms.entity.AuditAction;
import com.library.lms.entity.AuditEvent;
import com.library.lms.entity.AuditOutcome;
import com.library.lms.entity.AuditTargetType;
import com.library.lms.entity.Library;
import com.library.lms.repository.AuditEventRepository;
import com.library.lms.repository.LibraryRepository;

/**
 * The audit boundary's own rules, with no database: what an event carries,
 * which transaction each kind of event runs in, and why none can hold a
 * secret.
 */
class AuditServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-09-19T06:30:00Z"), ZoneId.of("Asia/Kolkata"));

    private final AuditEventRepository events = mock(AuditEventRepository.class);

    private final LibraryRepository libraries = mock(LibraryRepository.class);

    private final AuditService service = new AuditService(events, libraries, FIXED);

    private AuditEvent saved() {
        ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
        verify(events).save(event.capture());
        return event.getValue();
    }

    private Library library(long id) {
        Library library = new Library();
        library.setId(id);
        when(libraries.getReferenceById(id)).thenReturn(library);
        return library;
    }

    // ---------- what an event carries ----------

    @Test
    void aSuccessCarriesItsLibraryActorActionTargetAndTime() {
        Library library = library(7L);

        service.recordSuccess(AuditAction.USER_CREATED, 7L, 11L, AuditTarget.user(42L));

        AuditEvent event = saved();
        assertThat(event.getLibrary()).isSameAs(library);
        assertThat(event.getActorUserId()).isEqualTo(11L);
        assertThat(event.getAction()).isEqualTo(AuditAction.USER_CREATED);
        assertThat(event.getTargetType()).isEqualTo(AuditTargetType.USER);
        assertThat(event.getTargetId()).isEqualTo(42L);
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.SUCCESS);
        assertThat(event.getOccurredAt()).isEqualTo(LocalDateTime.now(FIXED));
    }

    @Test
    void aFailureIsMarkedAsOneAndMayHaveNeitherActorNorTarget() {
        library(7L);

        service.recordFailure(AuditAction.PASSWORD_RESET_BY_STAFF, 7L, null, AuditTarget.none());

        AuditEvent event = saved();
        assertThat(event.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
        assertThat(event.getActorUserId()).isNull();
        assertThat(event.getTargetType()).isNull();
        assertThat(event.getTargetId()).isNull();
    }

    @Test
    void aLibraryTargetIsNamedAsOne() {
        library(7L);

        service.recordSuccess(AuditAction.LIBRARY_CREATED, 7L, 11L, AuditTarget.library(8L));

        assertThat(saved().getTargetType()).isEqualTo(AuditTargetType.LIBRARY);
    }

    @Test
    void anEventWithoutALibraryActionOrTargetIsRefusedAndNothingIsSaved() {
        assertThatThrownBy(() -> service.recordSuccess(AuditAction.USER_CREATED, null, 11L, AuditTarget.none()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("library");
        assertThatThrownBy(() -> service.recordFailure(null, 7L, 11L, AuditTarget.none()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.recordSuccess(AuditAction.USER_CREATED, 7L, 11L, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(events, never()).save(any());
    }

    // ---------- which transaction each kind runs in ----------

    @Test
    void aSuccessMustJoinTheBusinessTransaction() throws Exception {
        Method success = AuditService.class.getMethod("recordSuccess", AuditAction.class, Long.class, Long.class,
                AuditTarget.class);

        assertThat(success.getAnnotation(Transactional.class).propagation())
                .as("committed with the change it describes, or rolled back with it - and never without one")
                .isEqualTo(Propagation.MANDATORY);
    }

    @Test
    void aFailureRunsInATransactionOfItsOwn() throws Exception {
        Method failure = AuditService.class.getMethod("recordFailure", AuditAction.class, Long.class, Long.class,
                AuditTarget.class);

        assertThat(failure.getAnnotation(Transactional.class).propagation())
                .as("so the refusal's rollback cannot erase the record of it")
                .isEqualTo(Propagation.REQUIRES_NEW);
    }

    // ---------- why an event cannot hold a secret ----------

    @Test
    void anEventHasNoFieldThatCouldHoldText() {
        List<Class<?>> types = new ArrayList<>();
        for (Field field : AuditEvent.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                types.add(field.getType());
            }
        }

        assertThat(types)
                .as("ids, enums, a library and a time - no String anywhere")
                .doesNotContain(String.class, char[].class, byte[].class)
                .allSatisfy(type -> assertThat(type == Long.class || type.isEnum() || type == LocalDateTime.class
                        || type == Library.class).as(type.getName()).isTrue());
    }

    @Test
    void theRecordingMethodsTakeNoText() {
        for (String name : List.of("recordSuccess", "recordFailure")) {
            Method method = Arrays.stream(AuditService.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();

            assertThat(method.getParameterTypes()).as(name).doesNotContain(String.class);
        }
    }

    @Test
    void theRepositoryCanAppendButNeverChangeOrRemove() {
        assertThat(CrudRepository.class.isAssignableFrom(AuditEventRepository.class))
                .as("no inherited delete, update or unscoped findAll")
                .isFalse();
        assertThat(Arrays.stream(AuditEventRepository.class.getDeclaredMethods()).map(Method::getName))
                .allMatch(name -> name.equals("save") || name.startsWith("findByLibraryId"));
    }
}
