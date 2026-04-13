import sys, os
sys.path.insert(0, ".")
from dotenv import load_dotenv
load_dotenv()

from app.database import SessionLocal, engine, Base
from app.models import User
from app.auth import hash_password

Base.metadata.create_all(bind=engine)

db = SessionLocal()
existing = db.query(User).filter(User.username == "admin").first()
if not existing:
    user = User(username="admin", password=hash_password("admin123"))
    db.add(user)
    db.commit()
    print("✅ User 'admin' ban gaya — password: admin123")
else:
    print("ℹ️  User already exists")
db.close()