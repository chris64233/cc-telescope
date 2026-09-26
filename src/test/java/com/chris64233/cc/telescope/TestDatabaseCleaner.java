package com.chris64233.cc.telescope;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 测试数据清理：reservations.preempted_by_id 与 preemptions.new_reservation_id 形成循环外键，
 * 不能直接 deleteAll。先清空关联列与抢占子表，再按外键顺序删除。
 */
@Component
public class TestDatabaseCleaner {

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public void clean() {
        entityManager.createNativeQuery("update reservations set preempted_by_id = null, "
                + "rearranged_from_id = null").executeUpdate();
        entityManager.createNativeQuery("update preemptions set new_reservation_id = null")
                .executeUpdate();
        entityManager.createNativeQuery("delete from preemption_reasons").executeUpdate();
        entityManager.createNativeQuery("delete from preemption_items").executeUpdate();
        entityManager.createNativeQuery("delete from reservations").executeUpdate();
        entityManager.createNativeQuery("delete from preemptions").executeUpdate();
        entityManager.createNativeQuery("delete from proposal_instruments").executeUpdate();
        entityManager.createNativeQuery("delete from telescope_instruments").executeUpdate();
        entityManager.createNativeQuery("delete from proposals").executeUpdate();
        entityManager.createNativeQuery("delete from telescopes").executeUpdate();
    }
}
