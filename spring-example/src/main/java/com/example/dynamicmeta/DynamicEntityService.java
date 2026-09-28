package com.example.dynamicmeta;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class DynamicEntityService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Map<String, Object> findById(String entityName, String idProperty, Object id) {
        return (Map<String, Object>) session()
                .createQuery("from " + entityName + " e where e." + idProperty + " = :id", Map.class)
                .setParameter("id", id)
                .uniqueResult();
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> findAll(String entityName) {
        List<?> rows = session().createQuery("from " + entityName, Map.class).getResultList();
        return (List<Map<String, Object>>) rows;
    }

    @Transactional
    public Map<String, Object> persist(String entityName, Map<String, Object> values) {
        session().persist(entityName, values);
        em.flush();
        return values;
    }

    private Session session() {
        return em.unwrap(Session.class);
    }
}
